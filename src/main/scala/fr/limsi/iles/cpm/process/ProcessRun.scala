package fr.limsi.iles.cpm.process

import java.io
import java.io.FilenameFilter
import java.util.UUID
import java.util.concurrent.Executors

import com.mongodb.BasicDBObject
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.typesafe.scalalogging.LazyLogging
import fr.limsi.iles.cpm.module.definition.{AnonymousDef, ModuleDef, ModuleManager}
import fr.limsi.iles.cpm.module.parameter.AbstractModuleParameter
import fr.limsi.iles.cpm.module.value.{AbstractParameterVal, DIR, VAL}
import fr.limsi.iles.cpm.module.value._
import fr.limsi.iles.cpm.server.{EventManager, EventMessage, Server}
import fr.limsi.iles.cpm.service.ServiceManager
import fr.limsi.iles.cpm.utils.Utils.FileWalker
import fr.limsi.iles.cpm.utils.{ConfManager, DB, Utils, YamlElt}
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import org.zeromq.ZMQ

import scala.io.Source
import scala.reflect.io.File
import scala.sys.process._
import scala.util.Random

abstract class ProcessStatus {
  override def toString():String={
    this.getClass.getSimpleName
  }
}
object ProcessStatus{
  implicit def fromString(serialized:String):ProcessStatus={
    """(\w+)(\((.*?)\))?""".r.findFirstMatchIn(serialized) match{
      case Some(matching)=> {
        matching.group(1) match{
          case "Running"=> Running()
          case "Exited" => Exited(matching.group(3))
          case _ => Waiting()
        }
      }
      case None => Waiting()
    }
  }
}
case class Running() extends ProcessStatus
case class Exited(exitcode:String) extends ProcessStatus {
  override def toString():String={
    this.getClass.getSimpleName+"("+exitcode+")"
  }
}
case class Waiting() extends ProcessStatus

sealed abstract class DetailedProcessStatusTree(val pname:String){
  override def toString() = toString("")
  def toString(implicit indent:String=""):String={
    this match {
      case DetailedProcessStatusNode(name,tree)=>{
        indent+name+" : "+tree.foldLeft("")((agg,node)=>{
          agg+"\n"+node.toString(indent+"  ")
        })
      }
      case DetailedProcessStatusLeaf(name,info)=>{
        indent + name + " : " + info
      }
    }
  }
}
case class DetailedProcessStatusNode(override val pname:String,val tree:List[DetailedProcessStatusTree]) extends DetailedProcessStatusTree(pname:String)
case class DetailedProcessStatusLeaf(override val pname:String,val info:String) extends DetailedProcessStatusTree(pname:String)


object AbstractProcess extends LazyLogging{
  var runningProcesses = Map[Int,AbstractProcess]()
  var portUsed = Array[String]()

  def newSockAddr(implicit local:Boolean=true) : String = {
    // TODO optimize
    var newport = Random.nextInt(65535)
    while(newport<1024 || portUsed.exists(newport.toString == _)){
      newport = Random.nextInt(65535)
    }
    if(local){
      "inproc://appfm-process"+String.valueOf(newport)
    }else{
      "tcp://*:" +String.valueOf(newport)
    }
  }

  def getStatus(process:Int)={
    runningProcesses(process)
  }

  def getResults(process:Int,param:String)={

  }

  def fromMongoDBObject(obj:BasicDBObject):AbstractProcess = {
    val uuid = UUID.fromString(obj.get("ruid").toString)
    val parentProcess : Option[AbstractProcess] = obj.get("parentProcess") match {
      case "None" => None
      case pid:String => Some(ProcessRunManager.getProcess(UUID.fromString(pid)))
      case _ => None
    }
    val env = RunEnv.deserialize(obj.getOrDefault("env","").toString)
    val parentEnv = RunEnv.deserialize(obj.getOrDefault("parentEnv","").toString)
    val runconf = RunEnv.deserialize(obj.getOrDefault("runconf","").toString)
    val namespace = obj.getOrDefault("modvalnamespace","").toString
    val modulename = obj.get("name").toString
    val modulevalconf = obj.get("modvalconf") match{
      case "" => None
      case x:String => try{
        Some((new Yaml).load(x).asInstanceOf[java.util.Map[String,Any]])
      }catch{
        case e:Throwable =>None
      }
      case _ => logger.warn("missing modval conf in serialized obj"); None
    }
    val parentPort = obj.getOrDefault("parentport","NONE") match {
      case "" => None
      case "NONE" => None
      case x:String => Some(x)
    }
    val owner = obj.getOrDefault("owner","_DEFAULT").toString
    val status : ProcessStatus = obj.getOrDefault("status","Waiting").toString
    val creationDate : java.time.LocalDateTime = java.time.LocalDateTime.parse(obj.getOrDefault("creationdate","").toString)
    val completedDate : java.time.LocalDateTime = {
      if(obj.getOrDefault("completeddate","").toString != ""){
        java.time.LocalDateTime.parse(obj.getOrDefault("completeddate","").toString)
      }else{
        null
      }
    }

    val log : String = obj.getOrDefault("log","").toString

    val x =  modulename match {
      case "_CMD" => new CMDProcess(new CMDVal(namespace,modulevalconf),parentProcess,uuid)
      case "_MAP" => new MAPProcess(new MAPVal(namespace,modulevalconf),parentProcess,uuid)
      case "_IF"=> new IFProcess(new IFVal(namespace,modulevalconf),parentProcess,uuid)
      //case "_ANONYMOUS" => new AnonymousModuleProcess(new ModuleVal(namespace,new AnonymousDef(),modulevalconf),parentProcess,uuid)
      case _ => new ModuleProcess(new ModuleVal(namespace,ModuleManager.modules(modulename),modulevalconf),parentProcess,uuid)
    }
    x.owner = owner
    x.status = status
    x.creationDate = creationDate
    x.completedDate = completedDate
    x.originalenv = runconf
    x.env = env
    x.parentEnv = parentEnv
    x.log=log
    x.progress = java.lang.Double.valueOf(obj.getOrDefault("progess","0.0").toString)
    x
  }

  def initEnvFrom(parentRunEnv:RunEnv,moduleval:AbstractModuleVal):RunEnv={

    // new env vars container
    var newargs = Map[String,AbstractParameterVal]()

    newargs ++= ServiceManager.ensureActive(moduleval.moduledef.require)

    // create new subdirectory run dir and set path to env vars
    val runresultdir = DIR(None,None)
    if(moduleval.moduledef.name=="_CMD" || moduleval.moduledef.name=="_IF"){
      runresultdir.fromYaml(parentRunEnv.getRawVar("_RUN_DIR").get.asString())
    }else{
      runresultdir.fromYaml(parentRunEnv.getRawVar("_RUN_DIR").get.asString()+"/"+moduleval.namespace)
    }
    val newdir = new java.io.File(runresultdir.asString())
    newdir.mkdirs()
    newargs += ("_RUN_DIR" -> runresultdir)

    // set module defintion directory info
    // builtin modules haven't any real definition directory, use parent's
    val defdir = if(ModuleDef.builtinmodules.contains(moduleval.moduledef.name) || moduleval.moduledef.name == "_ANONYMOUS"){
      parentRunEnv.getRawVar("_DEF_DIR").get
    }else{
      val x = DIR(None,None)
      x.fromYaml(moduleval.moduledef.defdir)
      x
    }
    newargs += ("_DEF_DIR" -> defdir)

    // set information about current running module name and caller context module name
    // for built in module, use name of first parent custom module name
    val (mod_context,cur_mod) = if(ModuleDef.builtinmodules.contains(moduleval.moduledef.name)){
      val x = VAL(None,None)
      x.fromYaml("_MAIN")
      (parentRunEnv.getRawVar("_CUR_MOD").getOrElse(x),parentRunEnv.getRawVar("_CUR_MOD").getOrElse(x))
    }else{
      val x = VAL(None,None)
      x.fromYaml(moduleval.moduledef.name)
      (parentRunEnv.getRawVar("_CUR_MOD").getOrElse(x),x)
    }
    newargs += ("_MOD_CONTEXT" -> mod_context)
    newargs += ("_CUR_MOD" -> cur_mod)

    // get usefull (look into module input needs) vars from parent env and copy them into the new env
    val donotoverride = List("_MOD_CONTEXT","_CUR_MOD","_DEF_DIR","_RUN_DIR")
    // for anonymous module, copy every parent env vars except previously set
    if(moduleval.moduledef.name == "_ANONYMOUS"){
      parentRunEnv.getVars().filter(arg => {
        !donotoverride.contains(arg._1)
      }).foreach(arg => {
        newargs += (arg._1 -> arg._2)
      })

    }else{
      moduleval.inputs.foreach(input=>{
        logger.info("Looking in parent env for "+input._1+" of type "+input._2.getClass.toGenericString+" with value to resolve : "+input._2.asString())
        val variables = input._2.extractVariables()
        var ready = true
        variables.filter(arg => {
          !donotoverride.contains(arg) && arg!="_"
        }).foreach(variable => {
          if(parentRunEnv.getRawVar(variable).isEmpty){
            ready = false
          }else{
            /*val value = if(moduleval.moduledef.inputs.contains(variable)){
              moduleval.moduledef.inputs(variable).createVal()
            }else{
              parentRunEnv.getRawVar(variable).get.newEmpty()
            }
            value.fromYaml(parentRunEnv.getRawVar(variable).get.asString())
            newargs += (variable -> value)*/
          }
        })
        if(ready){
          logger.info("Found")
          val newval = input._2.newEmpty()
          val yamlval = if(moduleval.moduledef.name == "_CMD"){
            parentRunEnv.resolveValueToString(input._2.toYaml())
          }else{
            parentRunEnv.resolveValueToYaml(input._2.toYaml())
          }
          newval.fromYaml(yamlval)
          newargs += (input._1 -> newval)
          moduleval.inputs(input._1).fromYaml(yamlval) // because

        }else{
          logger.info("Not found...")
        }
      });


    }

    //TODO allow previous run result to fill missing inputs if run type allow it

    // done in moduleval initialization
    moduleval.moduledef.inputs.filter(input => {
      !input._2.value.isEmpty && !newargs.contains(input._1)
    }).foreach(input => {
      logger.info("Adding default value for "+input._1)
      val value = input._2.createVal() //val value = moduleval.inputs(input._1) //
      value.fromYaml(RunEnv.resolveValueToYaml(newargs,input._2.value.get.toYaml()))
      newargs += (input._1 -> value)
    })


    // moduledef.inputs must be satisfied by inputs

    newargs.foreach(el => {
      if(
        moduleval.moduledef.inputs.exists(input=>{ // only check modules inputs
          el._1 == input._1
        })
          && (el._2._mytype=="FILE" || el._2._mytype=="DIR")){
        if(!(new java.io.File(el._2.asString())).exists()){
          throw new Exception(el._2.asString() + " does not exist! Aborting run")
        }
        if(!Utils.checkValidPath(el._2.asString())){
          throw new Exception(el._2.asString() + " is not an allowed path ! Aborting run")
        }
      }
    })

    new RunEnv(newargs)
  }

}

/**
 * Created by buiquang on 9/30/15.
 */
abstract class AbstractProcess(val parentProcess:Option[AbstractProcess],val id :UUID) extends LazyLogging{

  // TODO use these in initRunEnv to replace missing values (or force override values) and in step() method to skip proper modules
  var skipped = List[String]() // moduleval namespace to prevent from running and fetch previous result
  var replacements = Map[String,UUID]() // map (moduleval namespace -> run) result replacement

  var tags = List[String]()
  var creationDate = java.time.LocalDateTime.now()
  var completedDate : java.time.LocalDateTime = null
  var originalenv : RunEnv = null
  var parentEnv : RunEnv = null
  var env : RunEnv = null
  val moduleval : AbstractModuleVal
  var parentPort : Option[String] = None
  var processSockAddr :String = null
  val localSock = true
  var log = ""
  var detached = false
  var progress = 0.0
  var owner :String = null

  var childrenProcess = List[UUID]()

  var status : ProcessStatus = Waiting() // running | returncode
  var resultnamespace : String = null
//  var rawlog : List[String]

  def getOutput(outputName:String) = {
    env.getRawVar(outputName) match {
      case Some(thing) => thing
      case None => ""
    }
  }

  /**
   * @todo missing full parent path for embed modules (anonymous for eg.)
   * @param info
   */
  def log(info:String) : Unit={
    val parentNamespace = {
      if(this.parentProcess.isDefined){
        this.parentProcess.get.moduleval.namespace
      }else{
        ""
      }
    }
    getMasterProcess().log += parentNamespace+"|"+this.moduleval.namespace+" : "+info+"\n"
  }

  def getLog():String={
    log
  }

  /**
   * Retrieve the origin process that has spawn this current process
    *
    * @return the origin master process
   */
  def getMasterProcess() : AbstractProcess= {
    parentProcess match {
      case None => this
      case Some(process) => process.getMasterProcess()
    }
  }

  /**
    * todo
    */
  def signalProgressUpdate():Unit={
    val mp = getMasterProcess()
    val status = mp.moduleval.moduledef.name+mp.getDetailedStatus().toString()
    EventManager.emit(new EventMessage("process-update",mp.id.toString,status))
  }


  def getDetailedStatus():DetailedProcessStatusTree




  def kill():Unit
  protected[this] def postInit():Unit={}
  protected[this] def step():Unit
  protected[this] def update(message:ProcessMessage)
  protected[this] def endCondition() : Boolean
  protected[this] def updateParentEnv():Unit
  protected[this] def attrserialize():(Map[String,String],Map[String,String])
  protected[this] def attrdeserialize(mixedattrs:Map[String,String]):Unit

  def getRunDir():java.io.File={
    new java.io.File(env.getRawVar("_RUN_DIR").get.asString())
  }

  /**
   * Note : if changing (add or remove) mutable fields, you need to change the content of serializeToMongoObject method...
    *
    * @return a tuple containing immutable and mutable map fields to be serialized
   */
  def serialize()={
    var staticfields = List(
      "ruid" -> id.toString,
      "def" -> moduleval.moduledef.confFilePath,
      "name" -> moduleval.moduledef.name,
      "master" -> {parentProcess match {
        case Some(thing) => false
        case None => true
      }},
      "parentProcess" -> {
        if(parentProcess.isEmpty){
          "None"
        }else{
          parentProcess.get.id.toString
        }
      },
      "owner"->owner,
      "modvalconf" -> (new Yaml).dump(moduleval.conf.getOrElse("")),
      "modvalnamespace" -> moduleval.namespace,
      "resultnamespace" -> resultnamespace,
      "creationdate" -> creationDate.toString
    )
    var changingfields = List(
      "parentport" -> parentPort.getOrElse("NONE"),
      "processport" -> processSockAddr,
      "status" -> status.toString(),
      "children" -> childrenProcess.foldLeft("")((agg:String,el:UUID)=>{
        if(agg!="")
          agg+","+el.toString
        else
          el.toString
      }),
      "runconf"->{if(this.originalenv!=null)this.originalenv.serialize()else ""},
      "env" -> {
        env match {
          case x:RunEnv => x.serialize()
          case _ => ""
        }
      },
      "parentEnv" -> {
        parentEnv match {
          case x:RunEnv => x.serialize()
          case _ => ""
        }
      },
      "completeddate" -> {if(completedDate!=null){completedDate.toString}else{""}},
      "log" -> log,
      "progress" -> String.valueOf(progress)
      //"tags" =>
    )
    (staticfields,changingfields)
  }

  def serializeToJSON() : JSONObject = {
    val fields = serialize()
    var json = new JSONObject()
    fields._1.foreach(pair=>{
      json.put(pair._1,pair._2)
    })
    fields._2.foreach(pair=>{
      val value = if(pair._1 == "env" || pair._1 == "parentEnv" || pair._1 == "runconf"){
        YamlElt.fromJava(pair._2).toJSONObject()
      }else{
        pair._2
      }
      json.put(pair._1,value)
    })
    json
  }

  private def serializeToMongoObject(update:Boolean) : MongoDBObject = {
    var fields = serialize()
    var staticfields = fields._1
    var changingfields = fields._2
    var customattrs = this.attrserialize()
    staticfields :::= customattrs._1.toList
    changingfields :::= customattrs._2.toList

    val obj = if(update) {
      $set(changingfields(0),changingfields(1),changingfields(2),changingfields(3),changingfields(4),changingfields(5),changingfields(6),changingfields(7),changingfields(8),changingfields(9))
    }else{
      MongoDBObject(staticfields++changingfields)
    }
    obj
  }


  def getStatus(recursive:Boolean):String={
    if(recursive){
      this.moduleval.namespace+" : "+this.status.toString() +
        childrenProcess.reverse.foldLeft("")((agg,childid)=>{
          agg+"\n"+Utils.addOffset("\t",ProcessRunManager.getProcess(childid).getStatus(recursive))
        })
    }else{
      this.status.toString()
    }
  }


  def saveStateToDB() : Boolean = {
    val query = MongoDBObject("ruid"->id.toString)
    val result = if(ProcessRunManager.processCollection.find(query).count()>0){
      ProcessRunManager.processCollection.update(query,this.serializeToMongoObject(true))
    }else{
      ProcessRunManager.processCollection.insert(this.serializeToMongoObject(false))
    }

    // TODO check if everything went fine
    true
  }

  def setRun(parentEnv:RunEnv,ns:String,parentPort:Option[String],detached:Boolean):UUID = {
    ProcessRunManager.list += (id -> this)

    this.originalenv = parentEnv.copy()
    this.parentPort = parentPort
    this.parentEnv = parentEnv
    this.detached = detached
    resultnamespace = ns
    this.id
  }

  def run():UUID={
    status match {
      case Running() => throw new Exception("Process already running")
      case Waiting() => status = Running()
      case Exited(errorcode) => throw new Exception("Process already run and exited with status code "+errorcode)
    }
    logger.info("Executing "+moduleval.moduledef.name)

    try{
      initRunEnv()
      postInit()
    }catch{
      case e:Throwable => logger.error(e.getMessage); exitRoutine("error when initiation execution environment : "+e.getMessage); return id

    }



    if(detached){
      //logger.debug("Launching detached supervisor")

      val process = ProcessRunManager.executorService.execute(new Runnable {
        override def run(): Unit = {
          runSupervisor()
        }
      })
      // TODO new thread stuff etc.
    }else{
      runSupervisor()
    }
    id
  }

  def run(parentEnv:RunEnv,ns:String,parentPort:Option[String],detached:Boolean):UUID = {

    setRun(parentEnv,ns,parentPort,detached)

    // save process to db
    //this.saveStateToDB()
    run()


  }





  private def runSupervisor() = {
    val socket = Server.context.socket(ZMQ.PULL)
    var connected = 10
    while(connected!=0){
      try {
        processSockAddr = AbstractProcess.newSockAddr(localSock)
        socket.bind(processSockAddr)
        AbstractProcess.portUsed = AbstractProcess.portUsed :+ processSockAddr
        connected = 0
      }catch {
        case e:Throwable => {
          connected -= 1
          logger.info("Couldn't connect at port "+processSockAddr+" retrying (try : "+(10-connected)+")")
        }
      }
    }
      logger.info("New supervisor at port " + processSockAddr + " for module " + moduleval.moduledef.name)

    var error = "0"
    try{
      while (!endCondition()) {

        step() // step run

        val rawmessage = socket.recvStr()
        val message: ProcessMessage = rawmessage

        update(message) // update after step

      }



    }catch{
      case e:Throwable => error = "error when running : "+e.getMessage; logger.error(e.getMessage); log(e.getMessage)
    }finally {
      socket.close();
      AbstractProcess.portUsed = AbstractProcess.portUsed.filter(_ != processSockAddr)
      exitRoutine(error)
    }
  }

  protected def initRunEnv() = {

    //logger.debug("Initializing environement for "+moduleval.moduledef.name)
    //logger.debug("Parent env contains : ")
    parentEnv.debugPrint()





    env = AbstractProcess.initEnvFrom(parentEnv,moduleval)
    //logger.debug("Child env contains : ")
    env.debugPrint()
  }

  private[this] def exitRoutine(): Unit = exitRoutine("0")

  private[this] def exitRoutine(error:String): Unit = {
    progress=1.0
    logger.info("Finished processing for module "+moduleval.moduledef.name+", connecting to parent socket")
    val socket = parentPort match {
      case Some(addr) => {
        val socket = Server.context.socket(ZMQ.PUSH)
        socket.connect(addr.replace("*","localhost"))
        Some(socket)
      }
      case None => {
        // this is top level module run = "pipeline"
        None
      }
    }

    var catchUpdateParentEnvErrorMessage = error

    //logger.debug("Setting results to parent env")
    // set outputs value to env
    try{
      updateParentEnv()
    }catch{
      case e:Exception => {catchUpdateParentEnvErrorMessage+="\nError when updating parent environment\n"+e.getMessage}
    }



    status = Exited(error)
    ProcessRunManager.list -= id
    //saveStateToDB()


    socket match {
      case Some(sock) => {
        //logger.debug("Sending completion signal")
        sock.send(new ValidProcessMessage(moduleval.namespace,"FINISHED",catchUpdateParentEnvErrorMessage))
        sock.close()
      }
      case None => {
        //logger.info("Finished executing "+moduleval.moduledef.name)
      }
    }

  }


}






class ModuleProcess(override val moduleval:ModuleVal,override val parentProcess:Option[AbstractProcess],override val id:UUID) extends AbstractProcess(parentProcess,id){
  def this(moduleval:ModuleVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())

  var runningModules = Map[String,AbstractProcess]()
  var completedModules = Map[String,AbstractProcess]()
  val lock = new Object()

  override def kill():Unit={
    lock.synchronized{
      runningModules.foreach((el)=>{
        el._2.kill()
      })
    }
  }

  override def endCondition():Boolean={
    completedModules.size == moduleval.moduledef.exec.size
  }


  override def update(message:ProcessMessage)={
    message match {
      case ValidProcessMessage(sender,status,exitval) => status match {
        case "FINISHED" => {
          //logger.debug(sender + " just finished")
          // TODO message should contain new env data?
          // anyway update env here could be good since there is no need to lock...
          if(exitval!="0"){
            throw new Exception(sender+" failed with exit value "+exitval)
          }
          completedModules += (sender -> runningModules(sender))
        }
        case s : String => logger.warn("WTF? : "+s)
      }
      case _ => logger.warn("unrecognized message")
    }
  }




  /**
   *
   */
  override def step() = {
    //logger.debug("Trying to run next submodule for module "+moduleval.moduledef.name)
    lock.synchronized{
      val module = moduleval.moduledef.exec(completedModules.size)
      if(module.isExecutable(env)){
        //logger.debug("Launching "+module.moduledef.name)
        val process = module.toProcess(Some(this))
        if(module.moduledef.name=="_MAP"){
          process.asInstanceOf[MAPProcess].parentInputsDef = moduleval.moduledef.inputs
          var context = List[AbstractModuleVal]()
          runningModules.foreach(elt => {
            context ::= elt._2.moduleval
          })
          process.asInstanceOf[MAPProcess].context = context
        }else if(module.moduledef.name=="_IF"){
          process.asInstanceOf[IFProcess].parentInputsDef = moduleval.moduledef.inputs
          var context = List[AbstractModuleVal]()
          runningModules.foreach(elt => {
            context ::= elt._2.moduleval
          })
          process.asInstanceOf[IFProcess].context = context
        }else if(module.moduledef.name=="_WALKMAP"){
          process.asInstanceOf[WALKMAPProcess].parentInputsDef = moduleval.moduledef.inputs
          var context = List[AbstractModuleVal]()
          runningModules.foreach(elt => {
            context ::= elt._2.moduleval
          })
          process.asInstanceOf[WALKMAPProcess].context = context
        }
        runningModules += (module.namespace -> process)
        //childrenProcess ::= process.id
        //this.saveStateToDB()
        process.setRun(env,moduleval.namespace,Some(processSockAddr),true)
        ProcessManager.addToQueue(process)
      }else{
        throw new Exception("couldn't continue execution env doesn't provide necessary inputs..")
      }
      // this code only takes into account runnable module based on variables existence
      // it can run multiple modules in parallel not taking into account the execution description modules order
      /*
      val runnableModules = moduleval.moduledef.exec.filter(module => {
        if(runningModules.contains(module.namespace)){
          false
        }else{
          module.isExecutable(env)
        }
      });
      runnableModules.foreach(module => {
        logger.debug("Launching "+module.moduledef.name)
        val process = module.toProcess(Some(this))
        if(module.moduledef.name=="_MAP"){
          process.asInstanceOf[MAPProcess].parentInputsDef = moduleval.moduledef.inputs
          var context = List[AbstractModuleVal]()
          runningModules.foreach(elt => {
            context ::= elt._2.moduleval
          })
          process.asInstanceOf[MAPProcess].context = context
        }
        runningModules += (module.namespace -> process)
        //childrenProcess ::= process.id
        //this.saveStateToDB()
        process.run(env,moduleval.namespace,Some(processPort),true) // not top level modules (called by cpm cli) always run demonized
      })*/

    }
  }

  override def updateParentEnv() = {
    //logger.debug("Process env contains : ")
    env.debugPrint()
    moduleval.moduledef.outputs.foreach(output=>{
      //logger.debug("Looking to resolve : "+output._2.value.get.asString())
      val x = output._2.createVal()
      //logger.debug("Found :"+env.resolveValueToYaml(output._2.value.get.asString()))

      x.fromYaml(env.resolveValueToYaml(output._2.value.get.asString())) // changed toYaml to asString (for list bug...)
      val namespace = moduleval.namespace match {
        case "" => ""
        case _ => moduleval.namespace+"."
      }
      parentEnv.setVar(namespace+output._1,x)
    });
    //logger.debug("New parent env contains : ")
    parentEnv.debugPrint()
  }

  override protected[this] def attrserialize(): (Map[String, String], Map[String, String]) = {
    (Map[String, String](),Map[String, String]())
  }

  override protected[this] def attrdeserialize(mixedattrs: Map[String, String]): Unit = {
  }

  override def getDetailedStatus(): DetailedProcessStatusTree = {
    this.status match {
      case Exited(code) => DetailedProcessStatusLeaf(moduleval.namespace,"exited ("+code+")")
      case _ =>
        DetailedProcessStatusNode(moduleval.namespace, moduleval.moduledef.exec.foldRight(List[DetailedProcessStatusTree]())((moduleval, list) => {
          DetailedProcessStatusLeaf(moduleval.namespace, "waiting") :: list
        }).map(node => {
          if (runningModules.keySet.exists(_ == node.pname)) {
            runningModules(node.pname).status match {
              case Running() => {
                runningModules(node.pname).getDetailedStatus()
              }
              case Exited(exitcode) => {
                runningModules(node.pname).getDetailedStatus()
              }
              case _ => {
                node
              }
            }
          } else {
            node
          }
        }))
    }

  }
}

class AnonymousModuleProcess(override val moduleval:ModuleVal,override val parentProcess:Option[AbstractProcess],override val id:UUID)  extends ModuleProcess(moduleval,parentProcess,id){
  def this(moduleval:ModuleVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())

  override def updateParentEnv()= {
    //logger.debug("Process env contains : ")
    env.debugPrint()
    if(true || !moduleval.namespace.startsWith("_MAP")){
      moduleval.inputs.foreach(elt => {
        //logger.debug(elt._1+" with value "+elt._2.asString())
      })
      parentEnv.setVars(env.getVars().filter(elt => {

        moduleval.moduledef.exec.foldLeft(false)((agg,modval) => {
          agg || elt._1.startsWith(modval.namespace+".") // dot added to be sure that it is this very module and not another begiging with the same name
        })

      }).foldLeft(Map[String,AbstractParameterVal]())((map,elt)=>{map + (moduleval.namespace+"."+elt._1->elt._2)}))

    }
    //logger.debug("New parent env contains : ")
    parentEnv.debugPrint()
  }
}


class CMDProcess(override val moduleval:CMDVal,override val parentProcess:Option[AbstractProcess],override val id:UUID) extends AbstractProcess(parentProcess,id){
  def this(moduleval:CMDVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())
  override val localSock = false
  var stdoutval : VAL = VAL(None,None)
  var stderrval : VAL = VAL(None,None)
  var launched = ""
  var processCMDMessage : ProcessCMDMessage = null
  private var _lock = new Object()

  override def kill():Unit={
    _lock.synchronized{
      if(processCMDMessage != null){
        ProcessManager.kill(processCMDMessage.id.toString)
      }
    }
  }

  def getDockerImage(defdir:String)={
    env.resolveValueToString(moduleval.inputs("DOCKERFILE").toYaml()) match {
      case x :String => {
        if(x!="false"){
          // replace @ by "_at_" (docker doesn't accept @ char)
          val dockerfile = new java.io.File(defdir+"/"+x)
          val dockerfilename = if (dockerfile.exists()){
            dockerfile.getName
          }else{
            "Dockerfile"
          }
          val name = DockerManager.nameToDockerName(env.getRawVar("_MOD_CONTEXT").get.asString()+"-"+moduleval.namespace+"-"+dockerfilename) // _MOD_CONTEXT should always be the module defintion that holds this command
          if(!DockerManager.exist(name)){
            DockerManager.build(name,defdir+"/"+dockerfilename)
          }
          name
        }else{
          ""
        }
      }
      case _ =>  ""
    }
  }

  override def step(): Unit = {
    //logger.debug("Launching CMD "+env.resolveValueToString(moduleval.inputs("CMD").asString()))
    _lock.synchronized{
      var stderr = ""
      var stdout = ""
      val defdir = env.getRawVar("_DEF_DIR").get.asString()
      val wd = env.getRawVar("_RUN_DIR").get.asString()
      val deffolder = new java.io.File(defdir)
      val runfolder = new java.io.File(wd)


      val dockerimagename = getDockerImage(defdir)


      val unique = (env.resolveValueToString(moduleval.inputs("CONTAINED").toYaml()) == "true")
      val cmd = env.resolveValueToString(moduleval.inputs("CMD").asString()).replace("\\$","$")

      val image = if(dockerimagename!=""){
        Some(dockerimagename)
      }else{
        None
      }

      val port = {
        val items = processSockAddr.split(":")
        items.takeRight(1)(0)
      }
      processCMDMessage = new ProcessCMDMessage(
        this.id,
        moduleval.namespace,
        port,
        cmd,
        image,
        deffolder,
        runfolder,
        env.resolveValueToString(moduleval.inputs("DOCKER_OPTS").asString()),
        unique,
        "STARTED"
      )

      logger.info("sending command to be executed : "+cmd+"\n")
      processCMDMessage.send()

      // tag to prevent running more than once the process
      launched = "true"
    }


  }

  override protected[this] def updateParentEnv(): Unit = {

    try{
      val stdoutfile = Source.fromFile("/tmp/out"+this.id.toString)
      val stderrfile = Source.fromFile("/tmp/err"+this.id.toString)
      stdoutval.rawValue = stdoutfile.getLines().foldLeft("")(_+"\n"+_).trim
      stderrval.rawValue = stderrfile.getLines().foldLeft("")(_+"\n"+_).trim

      stdoutfile.close()
      stderrfile.close()


      //stdoutval.rawValue = Source.fromFile("/tmp/out"+this.id.toString).getLines.mkString
      stdoutval.resolvedValue = stdoutval.rawValue

      //stderrval.rawValue = Source.fromFile("/tmp/err"+this.id.toString).getLines.mkString
      stderrval.resolvedValue = stderrval.rawValue


      parentEnv.setVar(moduleval.namespace+".STDOUT", stdoutval)
      parentEnv.setVar(moduleval.namespace+".STDERR", stderrval)

      // delete all files created for this process
      Utils.deleteFile("/tmp/out"+this.id.toString)
      Utils.deleteFile("/tmp/err"+this.id.toString)
      Utils.deleteFile("/tmp/pinfo"+this.id.toString)

      if(stderrval.rawValue.trim!=""){
        log(stderrval.rawValue)
      }
    }catch{
      case e:Throwable => logger.error("Error when fetching default stdout and stderr of cmd process!")
    }



  }

  override protected[this] def update(message: ProcessMessage): Unit = {
    message match {
      case ValidProcessMessage(sender,status,exitval) => status match {
        case "FINISHED" => {
          //logger.debug(sender + " just finished")
          processCMDMessage.end()
          if(exitval!="0"){
            throw new Exception(sender+" failed with exit value "+exitval)
          }
        }
        case s : String => logger.warn("WTF? : "+s)
      }
      case _ => logger.warn("unknown message type")
    }
  }

  override protected[this] def endCondition(): Boolean = {
    launched != ""
  }

  override protected[this] def attrserialize(): (Map[String, String], Map[String, String]) = {
    (Map[String,String](),Map[String,String]("cmdprocrun"->launched))
  }

  override protected[this] def attrdeserialize(mixedattrs: Map[String, String]): Unit = {
    launched = mixedattrs("cmdprocrun")
  }

  override def getDetailedStatus(): DetailedProcessStatusTree = {
    DetailedProcessStatusLeaf(moduleval.namespace,String.valueOf(progress))
  }
}



class MAPProcess(override val moduleval:MAPVal,override val parentProcess:Option[AbstractProcess],override val id:UUID) extends AbstractProcess(parentProcess,id){
  def this(moduleval:MAPVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())
  var values = Map[String,Any]()

  var offset = 0
  var parentInputsDef : Map[String,AbstractModuleParameter] = Map[String,AbstractModuleParameter]()
  var context : List[AbstractModuleVal] = List[AbstractModuleVal]()

  private val _lock = new Object()
  private var _kill = false
  override def kill():Unit={
    _lock.synchronized{
      _kill = true
      var list = values("process").asInstanceOf[List[AbstractProcess]]
      list.foreach((p)=>{
        p.kill()
      })
    }
  }

  override def postInit():Unit={
    values += ("chunksize" -> Integer.valueOf(ConfManager.get("maxproc").toString))
    //val modvals = moduleval.inputs("RUN").asInstanceOf[LIST[MODVAL]]
    val modvals = env.getRawVar("RUN").get.asInstanceOf[LIST[MODVAL]]
    values += ("modules" -> AbstractParameterVal.paramToScalaListModval(modvals))
    values += ("process" -> List[AbstractProcess]())
    values += ("pcount"->0)
    values += ("completed" -> 0)
    //values += ("tmpenv"->env.copy())
    //val filterregex = moduleval.getInput("REGEX",env).asString();
    val filterregex = env.getRawVar("REGEX").get.asString();
    //values += ("filteredDir" -> new java.io.File(moduleval.getInput("IN",env).asString()).listFiles(new FilenameFilter {
    values += ("filteredDir" -> new java.io.File(env.getRawVar("IN").get.asString()).listFiles(new FilenameFilter {
      override def accept(dir: io.File, name: JSFunction): Boolean = {
        filterregex.r.findFirstIn(name) match {
          case None => false
          case Some(x:String) => true
        }
      }
    }))
    val module = new AnonymousDef(values("modules").asInstanceOf[List[AbstractModuleVal]],context,parentInputsDef)
    values += ("module"->module)
  }



  override protected[this] def updateParentEnv(): Unit = {
    //logger.debug("Process env contains : ")
    env.debugPrint()


    val prefix = "_MAP."
    val prefixlength = prefix.length
    val args = env.getVars()
    //val args = getResult().getVars()
    parentEnv.setVars(args.filter(elt => {
      elt._1.startsWith(prefix)
    }).groupBy[String](el=>{
      val modnamestartindex = el._1.substring(prefixlength).indexOf(".")
      val modnameendindex = el._1.substring(prefixlength+modnamestartindex+1).indexOf(".")
      prefix+el._1.substring(5+modnamestartindex+1)//.substring(0,modnameendindex)
    }).transform((key,content) => {
      val newel = AbstractModuleParameter.createVal(content.head._2._mytype+"*",content.head._2.format,content.head._2.schema).asInstanceOf[LIST[AbstractParameterVal]]
      content.foldLeft(newel)((agg,elt) => {
        agg.list ::= elt._2
        agg
      })
    }))

    //logger.debug("New parent env contains : ")
    parentEnv.debugPrint()
  }

  // @todo update process list (removed)
  override protected [this] def update(message:ProcessMessage)={
    val n : Int= values("completed").asInstanceOf[Int]
    values += ("chunksize" -> 1)
    values += ("completed" -> (n+1))
    progress = (n+1).asInstanceOf[Double]/values("filteredDir").asInstanceOf[Array[java.io.File]].length
  }

  override protected[this] def endCondition():Boolean = {
    offset>=values("filteredDir").asInstanceOf[Array[java.io.File]].length &&
      values("completed").asInstanceOf[Int] == values("pcount").asInstanceOf[Int] //(values("process").asInstanceOf[List[AbstractProcess]]).length
  }

  def getResult() = {
    val toProcessFiles = values("filteredDir").asInstanceOf[Array[java.io.File]]
    val resEnv = new RunEnv(Map[String,AbstractParameterVal]())
    var i = 0;

    //val newenv = values("tmpenv").asInstanceOf[RunEnv]
    toProcessFiles.foreach(file => {

      val newenv = env
      val dirinfo = this.moduleval.getInput("IN",env)
      val x = FILE(dirinfo.format,dirinfo.schema)
      x.fromYaml(file.getCanonicalPath)
      newenv.setVar("_", x)

      val module = new AnonymousDef(values("modules").asInstanceOf[List[AbstractModuleVal]],context,parentInputsDef)

      val moduleval = new ModuleVal("_MAP."+Utils.getNamespace(offset+i),module,Some(Utils.scalaMap2JavaMap(newenv.getVars().mapValues(paramval => {
        paramval.toYaml()
      }))))
      i+=1


      moduleval.moduledef.exec.foreach(modval => {
        val resolvebaseenv = AbstractProcess.initEnvFrom(newenv,modval)
        modval.moduledef.outputs.foreach(output => {
          resEnv.setVar("_MAP."+(offset+i).toString+"."+moduleval.namespace+output._1,resolvebaseenv.resolveValue(output._2.value.get))
        })
      })

    })

    resEnv
  }

  // @todo update process list
  override protected[this] def step()={
    val to = if(offset+values("chunksize").asInstanceOf[Int]>=values("filteredDir").asInstanceOf[Array[java.io.File]].length){
      values("filteredDir").asInstanceOf[Array[java.io.File]].length
    }else{
      offset + values("chunksize").asInstanceOf[Int]
    }

    val toProcessFiles = values("filteredDir").asInstanceOf[Array[java.io.File]].slice(offset,to)

    var i = 0;

    _lock.synchronized{
      if(_kill){
        throw new Exception("killed")
      }
    }

    //val newenv = values("tmpenv").asInstanceOf[RunEnv]
    toProcessFiles.foreach(file => {
      val newenv = env.copy()
      val dirinfo = this.moduleval.getInput("IN",env)
      val x = FILE(dirinfo.format,dirinfo.schema)
      x.fromYaml(file.getCanonicalPath)
      newenv.setVar("_", x)
      val module = values("module").asInstanceOf[AnonymousDef]
      //logger.debug("anonymous created")
      val moduleval = new ModuleVal("_MAP."+Utils.getNamespace(offset+i),module,Some(Utils.scalaMap2JavaMap(newenv.getVars().mapValues(paramval => {
        paramval.toYaml()
      }))))
      i+=1
      val process = new AnonymousModuleProcess(moduleval,Some(this))
      //childrenProcess ::= process.id
      //this.saveStateToDB()
      /*var list = values("process").asInstanceOf[List[AbstractProcess]]
      list ::= process
      values += ("process" -> list)*/
      values += ("pcount" -> (1+values("pcount").asInstanceOf[Int]))
      process.setRun(newenv,moduleval.namespace,Some(processSockAddr),true)
      ProcessManager.addToQueue(process)
    })

    offset = to
  }



  override protected[this] def attrserialize(): (Map[String, String], Map[String, String]) = {
    (Map[String,String](),Map[String,String]())
  }

  override protected[this] def attrdeserialize(mixedattrs: Map[String, String]): Unit = {

  }

  def swapEnv(): Unit ={
    val swapcol = DB.get("envswap")
    val query = MongoDBObject("ruid"->this.id.toString)



  }

  override def getDetailedStatus(): DetailedProcessStatusTree = {
    DetailedProcessStatusLeaf(moduleval.namespace,String.valueOf(progress))
  }

}

class WALKMAPProcess(override val moduleval:WALKMAPVal,override val parentProcess:Option[AbstractProcess],override val id:UUID) extends AbstractProcess(parentProcess,id) {
  def this(moduleval:WALKMAPVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())

  var values = Map[String,Any]()

  var offset = 0
  var parentInputsDef : Map[String,AbstractModuleParameter] = Map[String,AbstractModuleParameter]()
  var context : List[AbstractModuleVal] = List[AbstractModuleVal]()

  private val _lock = new Object()
  private var _kill = false
  override def kill():Unit={
    _lock.synchronized{
      _kill = true
      var list = values("process").asInstanceOf[List[AbstractProcess]]
      list.foreach((p)=>{
        p.kill()
      })
    }
  }

  override def getDetailedStatus(): DetailedProcessStatusTree = {
    DetailedProcessStatusLeaf(moduleval.namespace,String.valueOf(progress))
  }

  override def postInit():Unit={
    values += ("chunksize" -> Integer.valueOf(ConfManager.get("maxproc").toString))
    val modvals = moduleval.inputs("RUN").asInstanceOf[LIST[MODVAL]]
    values += ("modules" -> AbstractParameterVal.paramToScalaListModval(modvals))
    values += ("process" -> List[AbstractProcess]())
    values += ("pcount"->0)
    values += ("completed" -> 0)
    //values += ("tmpenv"->env.copy())
    values += ("regex" -> moduleval.getInput("REGEX",env).asString())
    values += ("walker" -> new FileWalker(new java.io.File(moduleval.getInput("IN",env).asString())))
    val module = new AnonymousDef(values("modules").asInstanceOf[List[AbstractModuleVal]],context,parentInputsDef)
    values += ("module"->module)
  }

  // @todo update process list
  override protected[this] def update(message: ProcessMessage): Unit = {
    val n : Int= values("completed").asInstanceOf[Int]
    values += ("chunksize" -> 1)
    values += ("completed" -> (n+1))
    val walker = values("walker").asInstanceOf[Utils.FileWalker]
    progress = (n+1).asInstanceOf[Double]/(values("pcount").asInstanceOf[Int]+walker.curCount)
  }

  override protected[this] def attrserialize(): (Map[String, String], Map[String, String]) = (Map[String,String](),Map[String,String]())

  override protected[this] def updateParentEnv(): Unit = {
    val prefix = "_WALKMAP."
    val prefixlength = prefix.length
    val args = env.getVars()
    //val args = getResult().getVars()
    parentEnv.setVars(args.filter(elt => {
      elt._1.startsWith(prefix)
    }).groupBy[String](el=>{
      val modnamestartindex = el._1.substring(prefixlength).indexOf(".")
      val modnameendindex = el._1.substring(prefixlength+modnamestartindex+1).indexOf(".")
      prefix+el._1.substring(5+modnamestartindex+1)//.substring(0,modnameendindex)
    }).transform((key,content) => {
      val newel = AbstractModuleParameter.createVal(content.head._2._mytype+"*",content.head._2.format,content.head._2.schema).asInstanceOf[LIST[AbstractParameterVal]]
      content.foldLeft(newel)((agg,elt) => {
        agg.list ::= elt._2
        agg
      })
    }))
  }

  override protected[this] def attrdeserialize(mixedattrs: Map[String, String]): Unit = {}

  override protected[this] def endCondition(): Boolean = {
    val walker = values("walker").asInstanceOf[Utils.FileWalker]
    !walker.hasMore && values("completed").asInstanceOf[Int] == values("pcount").asInstanceOf[Int]
  }

  override protected[this] def step(): Unit = {
    _lock.synchronized{
      if(_kill){
        throw new Exception("killed")
      }
    }

    val n = values("chunksize").asInstanceOf[Int]

    val walker = values("walker").asInstanceOf[Utils.FileWalker]

    var i = 0;

    val test = new java.io.File(moduleval.getInput("IN",env).asString())

    walker.take(n,values("regex").toString).foreach(file => {
      val newenv = env.copy()
      val dirinfo = this.moduleval.getInput("IN",env)
      val x = FILE(dirinfo.format,dirinfo.schema)
      x.fromYaml(file.getCanonicalPath)
      newenv.setVar("_", x)
      val module = values("module").asInstanceOf[AnonymousDef]
      //logger.debug("anonymous created")
      val moduleval = new ModuleVal("_WALKMAP."+Utils.getNamespace(offset+i),module,Some(Utils.scalaMap2JavaMap(newenv.getVars().mapValues(paramval => {
        paramval.toYaml()
      }))))
      i+=1
      val process = new AnonymousModuleProcess(moduleval,Some(this))
      //childrenProcess ::= process.id
      //this.saveStateToDB()
      /*var list = values("process").asInstanceOf[List[AbstractProcess]]
      list ::= process
      values += ("process" -> list)*/
      values += ("pcount" -> (1+values("pcount").asInstanceOf[Int]))
      process.setRun(newenv,moduleval.namespace,Some(processSockAddr),true)
      ProcessManager.addToQueue(process)
    })

    offset = offset + i
  }
}

class IFProcess(override val moduleval:IFVal,override val parentProcess:Option[AbstractProcess],override val id:UUID) extends AbstractProcess(parentProcess,id){
  def this(moduleval:IFVal,parentProcess:Option[AbstractProcess]) = this(moduleval,parentProcess,UUID.randomUUID())

  var done = false
  var parentInputsDef : Map[String,AbstractModuleParameter] = Map[String,AbstractModuleParameter]()
  var context : List[AbstractModuleVal] = List[AbstractModuleVal]()
  var launchedMod : AbstractProcess = null

  override def kill():Unit={
    if(launchedMod!=null){
      launchedMod.kill()
    }
  }

  def executeSubmodules(pipeline:String) = {
    val modules = this.moduleval.inputs(pipeline).asInstanceOf[LIST[MODVAL]]
    val modlist = AbstractParameterVal.paramToScalaListModval(modules)

    val module = new AnonymousDef(modlist,context,parentInputsDef)

    val moduleval = new ModuleVal("_IF",module,Some(Utils.scalaMap2JavaMap(env.getVars().mapValues(paramval => {
      paramval.toYaml()
    }))))

    val process = new AnonymousModuleProcess(moduleval,Some(this))
    launchedMod = process
    //childrenProcess ::= process.id
    //this.saveStateToDB()
    /*var list = values("process").asInstanceOf[List[AbstractProcess]]
    list ::= process
    values += ("process" -> list)*/
    process.setRun(env,moduleval.namespace,Some(processSockAddr),true)
    ProcessManager.addToQueue(process)
  }

  override protected[this] def step(): Unit = {
    val cond = env.resolveValueToString(moduleval.inputs("COND").toYaml())
    cond.trim match {
      case "" => executeSubmodules("ELSE")
      case "0" => executeSubmodules("ELSE")
      case "false" => executeSubmodules("ELSE")
      case "False" => executeSubmodules("ELSE")
      case _ => executeSubmodules("THEN")
    }
  }

  override protected[this] def update(message: ProcessMessage): Unit = {
    done = true
  }

  override protected[this] def attrserialize(): (Map[String, String], Map[String, String]) = {
    (Map[String, String](), Map[String, String]())
  }

  override protected[this] def updateParentEnv(): Unit = {

    val prefix = "_IF."
    val prefixlength = prefix.length
    val args = env.getVars()
    //val args = getResult().getVars()
    parentEnv.setVars(args.filter(elt => {
      elt._1.startsWith("_IF")
    }).groupBy[String](el=>{
      val modnamestartindex = el._1.substring(4).indexOf(".")
      prefix+el._1.substring(4+modnamestartindex+1)//.substring(0,modnameendindex)
    }).transform((key,content) => {
      val newel = AbstractModuleParameter.createVal(content.head._2._mytype+"*",content.head._2.format,content.head._2.schema).asInstanceOf[LIST[AbstractParameterVal]]
      content.foldLeft(newel)((agg,elt) => {
        agg.list ::= elt._2
        agg
      })
    }))

  }

  override protected[this] def attrdeserialize(mixedattrs: Map[String, String]): Unit = {

  }

  override protected[this] def endCondition(): Boolean = {
    done
  }

  override def getDetailedStatus(): DetailedProcessStatusTree = {
    if(launchedMod!=null){
      launchedMod.getDetailedStatus()
    }else{
      DetailedProcessStatusLeaf(moduleval.namespace,"waiting")
    }

  }
}





