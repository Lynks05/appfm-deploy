package fr.limsi.iles.cpm.module.definition

import java.io.{BufferedWriter, FileWriter, File, FileInputStream}

import com.typesafe.scalalogging.LazyLogging
import fr.limsi.iles.cpm.server.{EventMessage, EventManager}
import fr.limsi.iles.cpm.utils._
import org.json.{JSONArray, JSONObject}
import org.yaml.snakeyaml.Yaml
import fr.limsi.iles.cpm.service.ServiceManager

import scala.io.Source


class ModTree
case class ModLeaf(modName:String,modDefFilePath:String) extends ModTree
case class ModNode(modPath:String,modItems:List[ModTree]) extends ModTree

/**
 * Created by buiquang on 9/7/15.
 */
object ModuleManager extends LazyLogging{

  var modules : Map[String,ModuleDef]= Map[String,ModuleDef]()
  private var modulestree :ModNode = ModNode("/",List[ModTree]())
  val modulesCollection = DB.get("modules")

  /**
   * Check every modules found in the listed directory supposely containing modules definition/implementation/resources
   * Check for consistency
   */
  def init()={
    // list all modules and init independant fields
    if(!ConfManager.confMap.containsKey("modules_dir")){
      throw new Exception("no module directories set in configuration!")
    }

    val list : java.util.ArrayList[String] = ConfManager.get("modules_dir").asInstanceOf[java.util.ArrayList[String]]
    val iterator = list.iterator()
    var roots = List[ModTree]()
    while(iterator.hasNext){
      val path = iterator.next()
      val file = new File(path)
      if(file.exists()){
        findModuleConf(file,ModuleManager.initModule,ServiceManager.initService) match{
          case Some(x:ModTree)=>{
            roots = ModNode(file.getParent,x::modulestree.modItems) :: roots
          }
          case None => {

          }
        }
      }
    }
    modulestree = ModNode("",roots)

    var firstRun = true
    var discarded = ""
    while(discarded != "" || firstRun){
      if(discarded!=""){
        modules -= discarded
      }
      discarded = ""
      firstRun = false
      var curmod = ""
      try{
        modules.values.filter(module => {!ModuleDef.builtinmodules.contains(module.name)}).foreach(m => {
          curmod = m.name
          val yaml = new Yaml()
          val wd = (new File(m.confFilePath)).getParent
          val ios = new FileInputStream(m.confFilePath)
          val confMap = yaml.load(ios).asInstanceOf[java.util.Map[String,Any]]
            m.exec = ModuleDef.initRun(confMap,m.inputs)
        })
      }catch{
        case e:Throwable => discarded = curmod; e.printStackTrace(); logger.error("error when initiation exec configuration for module "+curmod+". This module will therefore be discarded");
      }

    }

    logger.info("Finished initializing modules")
  }

  /**
   * Reload module definition
   */
  def reload()={
    modules = Map[String,ModuleDef]()
    modulestree = ModNode("/",List[ModTree]())
    init()
  }

  /**
   * Create a module from a name, a location and yaml formatted data description
   * @param name
   * @param folderpath
   * @param data
   * @return a string indicating if creation was a success, an error message otherwise
   */
  def createModule(name:String,folderpath:String,data:String):String={
    val normalizeddata = data.replace("\t","  ")
    var response = new JSONObject()
    if(modules.contains(name)){
      response.put("error","name already exist")
      return response.toString()
    }

    YamlElt.fromJava(normalizeddata) match {
      case YamlMap(map)=>{
        val conffile = new java.io.File(folderpath+"/"+name+".module")
        initModule(name,map,conffile) match {
          case Some(modulename) => {
            try{
              conffile.getParentFile.mkdirs()
              val bw = new BufferedWriter(new FileWriter(conffile))
              bw.write(normalizeddata)
              bw.close()
              val module = modules(name)
              module.exec = ModuleDef.initRun(map,module.inputs)
            }catch{
              case e:Throwable => modules -= name; response.put("error","message : "+e.getMessage); response.put("cause",e.getStackTrace.toString)
            }
          }
          case None => {
            response.put("error","invalid configuration")
          }
        }
      }
      case _ => {
        response.put("error","invalid configuration file")
      }
    }

    if(modules.contains(name)){
      EventManager.emit(new EventMessage("module-added",name,folderpath))
      response.put("success",name)
      modulestree = insertInModuleTree(name,folderpath,modulestree,modulestree.modPath).get
    }

    response.toString()

  }

  /**
   * Update a module definition from a name and yaml formatted data description
   * @param name
   * @param data
   * @return a string indicating if modification was a success, an error message otherwise
   */
  def updateModule(name:String,data:String):String={
    val normalizeddata = data.replace("\t","  ")
    var response = new JSONObject()
    if(!modules.contains(name)){
      response.put("error","module doesn't exist")
      return response.toString()
    }

    val module = modules(name)
    val conffile = new java.io.File(module.confFilePath)
    var updatesuccess = false
    YamlElt.fromJava(normalizeddata) match {
      case YamlMap(map)=>{
        initModule(name,map,conffile)(false) match {
          case Some(modulename) => {
            var execisok = false
            try{
              val updatedmodule = modules(name)
              updatedmodule.exec = ModuleDef.initRun(map,updatedmodule.inputs)
              execisok = true
            }catch{
              case e:Throwable => response.put("error","message : "+e.getMessage);
            }
            if(execisok){
              try{
                if(conffile.canWrite){
                  val bw = new BufferedWriter(new FileWriter(conffile))
                  bw.write(normalizeddata)
                  bw.close()
                  updatesuccess = true
                }else{
                  response.put("error","cannot write to module defintion file!");
                }
              }catch{
                case e:Throwable => modules -= name; response.put("error","message : "+e.getMessage); response.put("cause",e.getStackTrace.toString)
              }
            }else{
              response.put("error","failed initializing run")
            }
          }
          case None => {
            response.put("error","invalid configuration")
          }
        }
      }
      case _ => {
        response.put("error","invalid configuration file")
      }
    }

    if(updatesuccess){
      EventManager.emit(new EventMessage("module-updated",name,normalizeddata))
      response.put("success",name)
    }

    response.toString()

  }

  def updateModuleDisplay(name:String,data:String):String={
    "not implemented"
  }

  def insertInModuleTree(modulename:String,moduledefparentpath:String,tree:ModNode,parentPath:String):Option[ModNode]={
    val currentPath = Utils.ensureTrailingSlash(parentPath)
    val moduledirPath = Utils.ensureTrailingSlash(moduledefparentpath)

    if(moduledirPath.startsWith(currentPath)){
      if(moduledirPath==currentPath){
        Some(ModNode(tree.modPath,ModLeaf(modulename,moduledirPath+modulename+".module")::tree.modItems))
      }else{
        var found = false
        val newitems = tree.modItems.map(subtree=>{
          subtree match{
            case x:ModLeaf=>x
            case x:ModNode => {
              insertInModuleTree(modulename,moduledefparentpath,x,currentPath+x.modPath) match{
                case None => x
                case Some(y) => found = true; y
              }
            }
          }
        })
        Some(ModNode(tree.modPath,{if(!found){
          moduledefparentpath.substring(currentPath.length).split("/").foldRight[ModTree](ModLeaf(modulename,moduledirPath+modulename+".module"))((path,agg)=>{
            ModNode(path,List[ModTree](agg))
          }) :: newitems
        }else{
          newitems
        }}))
      }
    }else{
      None
    }

  }

  def jsonTreeExport(tree:ModTree):Object = {
    tree match {
      case ModNode(dirpath,list) => {
        var json = new JSONObject()
        var array = new JSONArray()
        list.foreach(subtree => {
          array.put(jsonTreeExport(subtree))
        })
        json.put("folder",true)
        json.put("foldername",dirpath)
        json.put("items",array)
        json
      }
      case ModLeaf(name,filepath) => {
        if(modules.contains(name)){
          var json = new JSONObject()
          json.put("modulename",name)
          json.put("sourcepath",filepath)
          json.put("module",new JSONObject(modules(name).serialize()(true)))
          json.put("source",Source.fromFile(modules(name).confFilePath).getLines.foldLeft("")((agg,line)=>agg+"\n"+line))
        }else{
          var json = new JSONObject()
          json.put("modulename",name)
          json.put("sourcepath",filepath)
          json.put("source",Source.fromFile(filepath).getLines.foldLeft("")((agg,line)=>agg+"\n"+line))
        }
      }
    }
  }

  /**
   * Export module defs to json
   * @param onlyname
   * @return
   */
  def jsonExport(onlyname:Boolean):String ={
    /*var json ="["
    modules.foreach(el=>{
      if(onlyname){
        json += el._1 +","
      }else{
        json += el._2.serialize()(true)+","
      }
    })
    json.substring(0,json.length-1)+"]"*/
    jsonTreeExport(modulestree).toString
  }

  /**
   * Returns printable string of module defs
   * @param onlyname
   * @return
   */
  def ls(onlyname:Boolean) : String= {
    modules.foldRight("")((el,agg) => {
      {if(onlyname){
         el._2.name
      }else{
        el._2.toString
      }} + "\n" + agg
    })
  }

  /**
   * Apply a function on file that match module extension : .module
   * @param curFile
   * @param f
   */
  private def findModuleConf(curFile:java.io.File,f:java.io.File => Option[String],g:java.io.File=>Boolean) :Option[ModTree]={
    if(curFile.isFile){
      if(curFile.getName().endsWith(".module")){
        f(curFile) match {
          case Some(modulename)=>{
            Some(ModLeaf(modulename,curFile.getCanonicalPath))
          }
          case None =>{None}
        }
      }else if(curFile.getName().endsWith(".service")){
        g(curFile)
        None
      }else{
        None
      }
    }else if(curFile.isDirectory){
      var list = List[ModTree]()
      val iterator = curFile.listFiles().iterator
      while(iterator.hasNext){
        val file = iterator.next()
        findModuleConf(file,f,g) match{
          case Some(x:ModTree)=>{
            list = x :: list
          }
          case None => {

          }
        }
      }
      if(list.length > 0)
        Some(ModNode(curFile.getName,list))
      else
        None
    }else{
      logger.warn("File at path "+curFile.getPath+" is neither file nor directory!")
      None
    }
  }


  private def initModule(modulename:String,confMap:java.util.Map[String,Any],moduleConfFile:File)(implicit checkifexist : Boolean = true):Option[String]={
    var foundModule : Option[String] = None
    try{
      val module = new ModuleDef(moduleConfFile.getCanonicalPath,
        ModuleDef.initName(modulename,confMap),
        ModuleDef.initDesc(confMap),
        ModuleDef.initInputs(confMap),
        ModuleDef.initOutputs(confMap),
        ModuleDef.initLogs(confMap),
        Nil
      );
      // check if module name already exist

      modules.get(modulename) match {
        case Some(m:ModuleDef) => {
          if(checkifexist){
            throw new Exception("Module already exist, defined in "+moduleConfFile.getParent)
          }else{
            modules = modules.updated(modulename,module)
            Some(modulename)
          }
        }
        case None => foundModule = Some(modulename); modules += (modulename -> module); foundModule
      }

    }catch{
      case e: Throwable => e.printStackTrace(); logger.error("Wrong module defintion in "+moduleConfFile.getCanonicalPath+"\n"+e.getMessage+"\n This module will not be registered."); foundModule
    }
  }

  /**
   * Init module definition conf from definition file
   * @param moduleConfFile
   */
  private def initModule(moduleConfFile:File):Option[String]={
    try{
      val modulename = moduleConfFile.getName.substring(0,moduleConfFile.getName.lastIndexOf('.'))
      logger.debug("Initiating module "+modulename)

      if(ModuleDef.builtinmodules.contains(modulename)){
        modules += (modulename -> ModuleDef.builtinmodules(modulename));
        return Some(modulename)
      }

      val yaml = new Yaml()
      val ios = new FileInputStream(moduleConfFile)
      val confMap = yaml.load(ios).asInstanceOf[java.util.Map[String,Any]]
      initModule(modulename,confMap,moduleConfFile)
    }catch{
      case e: Throwable => e.printStackTrace(); logger.error("Wrong module defintion in "+moduleConfFile.getCanonicalPath+"\n"+e.getMessage+"\n This module will not be registered."); None
    }

  }



}
