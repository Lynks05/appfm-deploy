package fr.limsi.iles.cpm.module.value

import java.io.RandomAccessFile
import java.util.function.Consumer
import com.typesafe.scalalogging.LazyLogging
import fr.limsi.iles.cpm.module.definition.{ModuleManager, MAPDef, CMDDef}
import fr.limsi.iles.cpm.module.parameter.AbstractModuleParameter
import fr.limsi.iles.cpm.utils.{Utils, YamlString, YamlList, YamlElt}

import scala.collection
import scala.io.Source

/**
 * Abstract base class for param typed values
 * Primitive types are :
 * VAL
 * FILE
 * DIR
 * CORPUS
 * Container types are :
 * MAP
 * LIST
 */
sealed abstract class AbstractParameterVal(val format:Option[String],val schema:Option[String]){

  var yamlVal : Any = _
  val _mytype : String

  def fromYaml(yaml:Any)(implicit stringdeserialization:Boolean = false):Unit = {
    yamlVal = yaml
    parseYaml(yaml)(stringdeserialization)
  }

  protected def parseYaml(yaml:Any)(implicit stringdeserialization:Boolean = false):Unit

  def toYaml()(implicit serialization:Boolean = false):String

  def asString():String
  override def toString()=asString()

  def isExpression() : Boolean = {
    val value = asString().trim

    val complexVarsRemoved = """(?<!\\)\$\{(.+?)\}""".r.replaceFirstIn(value,"")
    if(complexVarsRemoved == ""){
      return false
    }
    val simpleVarsRemoved = """(?<!\\)\$([a-zA-Z_\-]+)""".r.replaceFirstIn(complexVarsRemoved,"")
    !(simpleVarsRemoved == "" && value.length == complexVarsRemoved.length)
  }

  def extractVariables() : Array[String]={
    val value = toYaml()
    val complexVars = """(?<!\\)\$\{(.+?)\}""".r.findAllMatchIn(value)
    val simpleVars = """(?<!\\)\$([a-zA-Z_\-]+)""".r.findAllMatchIn(value)
    var vars = Array[String]()
    while(complexVars.hasNext){
      val complexvar = complexVars.next()
      val splitted = complexvar.group(1).split(":")
      val complexvariable = if(splitted.length>1){
        (splitted.slice(0,splitted.length-1).mkString("."),splitted(splitted.length-1))
      }else{
        (complexvar.group(1),"")
      }
      vars :+= complexvariable._1
    }
    while(simpleVars.hasNext){
      vars :+= simpleVars.next().group(1)
    }
    vars
  }

  def getAttr(attrName:String):AbstractParameterVal

  def newEmpty():AbstractParameterVal={
    AbstractModuleParameter.createVal(_mytype,format,schema)
  }
}

object AbstractParameterVal{
  /*implicit def stringToVAL(stringval:String):ModuleParameterVal={
    val x = VAL()
    x.parseFromJavaYaml(stringval)
    x
  }*/
  def paramToScalaListModval(modulevallist:LIST[MODVAL]) : List[AbstractModuleVal]={
    var modulelist : List[AbstractModuleVal]= List[AbstractModuleVal]()
    modulevallist.list.foreach(modval=>{
      modulelist ::= modval.moduleval
    })
    modulelist.reverse
  }
}

/**
 * VAL are string value, they may be internally stored to save the result of a run but are passed as a full string value
 */
case class VAL(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) with LazyLogging {
  var rawValue : String = _
  var resolvedValue : String = _
  override val _mytype = "VAL"


  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {
    if(yaml == null){
      rawValue = ""
      return
    }
    val value :Option[String]= Some(String.valueOf(yaml))//YamlElt.readAs[String](yaml)
    rawValue = value match {
      case Some(theval:String) => {
        if(stringdeserialization){
          theval//.replace("""\"""",""""""").replace("""\\""","""\""")
        }else{
          theval
        }
      }
      case None => "Error reading val value (should be a string)"
    }
  }

  override def asString()={
    this.rawValue
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    throw new Exception("VAL doesn't have any attributes")
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    if(this.rawValue!=null){
      val tmp = this.rawValue.replace("\t","  ") // because when parsing back yaml tab are not well handled
      if(serialization){
        '"'+tmp.replace(""""""","""\"""").replace("\n","""\n""")+'"'
      }else{
        tmp
      }
    }else{
      logger.warn("null value for VAL")
      ""
    }
  }
}


case class STREAM(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) {
  override val _mytype: String = "STREAM"
  var namedPipe : String = _

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization: Boolean): Unit = ???

  override def asString(): String = ???

  override def getAttr(attrName: String): AbstractParameterVal = {
    attrName match{
      case "next" =>{
        val raf = new RandomAccessFile(namedPipe,"r")
        VAL(format,schema)
      }
    }
  }

  override def toYaml()(implicit serialization: Boolean): String = ???
}

/**
 * FILE are representated by their file path
 * they display the following properties :
 * - basename : the name of the file without last extension
 * - dirname : the name of its directory
 */
case class FILE(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) {
  var rawValue : String = _
  override val _mytype = "FILE"

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {
    val value = YamlElt.readAs[String](yaml)
    rawValue = value match {
      case Some(theval) => theval
      case None => "Error reading val value (should be a string)"
    }
  }

  override def asString()={
    rawValue
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    attrName match {
      case "content" => {
        val file = new java.io.File(rawValue)
        val bs = Source.fromFile(file.getCanonicalPath)
        val content = bs.getLines.mkString
        bs.close()
        val x = new VAL(format,schema)
        x.rawValue = content
        x
      }
      case "basename" => {
        val file = new java.io.File(rawValue)
        val extensionIndex = file.getName.lastIndexOf(".")
        if(extensionIndex != -1){
          val x = new VAL(Some("text"),None)
          x.fromYaml(file.getName.substring(0,extensionIndex))
          x
        }else{
          val x = new VAL(Some("text"),None)
          x.fromYaml(file.getName)
          x
        }
      }
      case "filename" => {
        val x = new VAL(Some("text"),Some("filepath"))
        val file = new java.io.File(rawValue)
        x.fromYaml(file.getName)
        x
      }
      case "basedir" => {
        val x = new DIR(Some("text"),Some("filepath"))
        val file = new java.io.File(rawValue)
        x.fromYaml(file.getParent)
        x
      }
    }
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    rawValue
  }
}



case class DIR(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) {
  var rawValue : String = _
  override val _mytype = "DIR"

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {
    val value = YamlElt.readAs[String](yaml)
    rawValue = value match {
      case Some(theval) => theval
      case None => "Error reading val value (should be a string)"
    }
  }

  override def asString()={
    rawValue
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    attrName match {
      case "ls" => {
        val x = new LIST[FILE](format,schema)
        val file = new java.io.File(rawValue)
        x.fromYaml(file.listFiles().foldLeft("")((str,thefile)=>{
          if(thefile.isFile)
            str + " " + thefile.getCanonicalPath
          else
            str
        }))
        x
      }
      case "rls" => {
        val x = new LIST[FILE](format,schema)
        val file = new java.io.File(rawValue)
        def recListFiles(folder:java.io.File) :String = {
          folder.listFiles().foldLeft("")((str,thefile)=>{
            if(thefile.isFile)
              str + " " + thefile.getCanonicalPath
            else if(thefile.isDirectory){
              str + recListFiles(thefile)
            }else {
              str
            }
          })
        }
        x.fromYaml(recListFiles(file))
        x
      }
    }
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    rawValue
  }
}

/**
 * CORPUS is for now a kind of directory object
 * it is representated by its path
 * it displays the following properties :
 * - list : the list of file contained in the corpus/directory
 */
case class CORPUS(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) {
  override val _mytype = "CORPUS"
  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {

  }

  override def asString()={
    "the corpus root dir path"
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    throw new Exception("VAL doesn't have any attributes")
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    ""
  }
}

case class DB(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema) {
  override val _mytype = "DB"



  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {
    // retrieve connection parameters
  }

  override def asString()={
    "username:password@host/dbname/collectionname"
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    throw new Exception("no such parameter exist for "+_mytype)
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    ""
  }
}


/**
 * MODULE is a ModuleVal represented by its yaml instanciation
 */
case class MODVAL(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema){
  override val _mytype = "MODVAL"
  var moduleval : AbstractModuleVal = _

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {
    moduleval = AbstractModuleVal.fromConf(yaml,List[AbstractModuleVal](),Map[String,AbstractModuleParameter]())
  }

  override def asString()={
    "\n"+moduleval.namespace+" :\n"+moduleval.inputs.foldLeft("")((prev,elt) => {
      prev + "  "+elt._1+" : "+Utils.addOffset("    ",elt._2.asString())+"\n"
    })
  }


  override def getAttr(attrName: String): AbstractParameterVal = {
    throw new Exception("VAL doesn't have any attributes")
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    moduleval.namespace+" :\n"+moduleval.inputs.foldLeft("")((prev,elt) => {
      prev + "  "+elt._1+" : "+Utils.addOffset("  ",elt._2.toYaml()(serialization))+"\n"
    })
  }
}


/**
 * LIST is a list of other paramval
 * it is represented as a string depending on the type of its content :
 * - VAR : quoted variables separated by spaces
 * - CORPUS/FILE : path separated by spaces
 * - MODULE : the yaml list of yaml moduleval instanciation
 */
case class LIST[P <: AbstractParameterVal](override val format:Option[String],override val schema:Option[String])(implicit manifest: Manifest[P]) extends AbstractParameterVal(format,schema) {
  override val _mytype = {
    getBaseType(manifest)+"*"
  }
  var list : List[P] = List[P]()

  def make: P = if(_mytype.endsWith("**")){
    manifest.runtimeClass.getConstructor(classOf[Option[String]],classOf[Option[String]],classOf[Manifest[_]]).newInstance(format,schema,manifest.typeArguments(0)).asInstanceOf[P]
  }else{
    manifest.runtimeClass.getConstructor(classOf[Option[String]],classOf[Option[String]]).newInstance(format,schema).asInstanceOf[P]
  }

  def getBaseType(manifest: Manifest[_]):String={
    if(manifest.typeArguments.length>0){
      getBaseType(manifest.typeArguments(0))+"*"
    }else{
      manifest.runtimeClass.getSimpleName
    }
  }

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {

    list = YamlElt.fromJava(yaml) match {
      case YamlList(list) => {
        var thelist :List[P]= List[P]()
        list.forEach(new Consumer[Any]() {

          override def accept(t: Any): Unit = {
            val el : P = make
            el.fromYaml(t)(stringdeserialization)
            thelist = thelist :+ el
          }
        })
        thelist
      }
      case YamlString(implicitlist) => {
        var thelist :List[P]= List[P]()
        implicitlist.split("\\s+").foreach(t => {
          val el: P = make
          el.fromYaml(t)(stringdeserialization)
          thelist = thelist :+ el
        })
        thelist
      }
      case _ => {
        throw new Exception("error when parsing LIST type")
      }
    }
  }

  override def asString()={
    list.foldLeft("")((str,el) => {
      str +" "+ el.asString()
    })
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    throw new Exception("VAL doesn't have any attributes")
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    list.foldLeft("")((str,el) => {
      str +"\n  - "+ Utils.addOffset("    ",el.toYaml()(serialization))
    })
  }
}

case class MAP(override val format:Option[String],override val schema:Option[String]) extends AbstractParameterVal(format,schema){
  override val _mytype = "MAP"
  var map : Map[String,AbstractParameterVal] = Map[String,AbstractParameterVal]()

  override protected def parseYaml(yaml: Any)(implicit stringdeserialization:Boolean = false): Unit = {

  }

  override def asString(): String = {
    "???"
  }

  override def getAttr(attrName: String): AbstractParameterVal = {
    if(map.contains(attrName)){
      map(attrName)
    }else{
      throw new Exception("no such attr value")
    }
  }

  override def toYaml()(implicit serialization:Boolean = false): String = {
    map.foldLeft("")((agg,el)=>{
      agg + "\n" + el._1 + " : " + el._2.toYaml()(serialization)
    })
  }
}


