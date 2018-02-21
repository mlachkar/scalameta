package scala.meta.internal.metacp

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale.LanguageRange
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer
import scala.meta.internal.metacp.Javacp.SignatureMode.Start
import scala.meta.internal.metacp.Javacp.SignatureMode.SuperClass
import scala.meta.internal.semanticdb3.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb3.SymbolInformation.{Property => p}
import scala.meta.internal.{semanticdb3 => s}
import scala.tools.asm.ClassReader
import scala.tools.asm.signature.SignatureReader
import scala.tools.asm.signature.SignatureVisitor
import scala.tools.asm.tree.ClassNode
import scala.tools.asm.tree.FieldNode
import scala.tools.asm.tree.MethodNode
import scala.tools.asm.{Opcodes => o}
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath

object Javacp {

  def array(tpe: s.Type): s.Type =
    ref("_root_.scala.Array#", tpe :: Nil)

  def ref(symbol: String, args: List[s.Type] = Nil): s.Type = {
    s.Type(
      s.Type.Tag.TYPE_REF,
      typeRef = Some(s.TypeRef(prefix = None, symbol, args))
    )
  }

  def enclosingPackage(name: String): String = {
    val slash = name.lastIndexOf('/')
    if (slash < 0) sys.error(name)
    else name.substring(0, slash)
  }

  def main(args: Array[String]): Unit = {
    run(args)
    //    val signature =
    //      "<T:Ljava/lang/Object;>(Ljava/util/ArrayList<Ljava/util/ArrayList<[TT;>;>;)Ljava/util/ArrayList<Ljava/util/ArrayList<[TT;>;>;"
    //    val sr = new SignatureReader(signature)
    //    val v = new SemanticdbSignatureVisitor
    //    sr.accept(v)
  }

  def run(args: Array[String]): Unit = {
    val root = AbsolutePath("core/target/scala-2.12/classes/test")
    val scopes = new Scopes()
    Files.walkFileTree(
      root.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (PathIO.extension(file) == "class") {

            val db = process(root.toNIO, file, scopes)
            if (!file.toString.contains('$')) {
              // pprint.log(db.toProtoString, height = 1000)
              // Main.pprint(db)
            }
          }
          FileVisitResult.CONTINUE
        }
      }
    )

  }

  def process(root: Path, file: Path, scopes: Scopes): s.TextDocument = {
    val bytes = Files.readAllBytes(file)
    val node = asmNodeFromBytes(bytes)
    val buf = ArrayBuffer.empty[s.SymbolInformation]

    val classSymbol = ssym(node.name)
    val className = getName(node.name)
    val isTopLevelClass = !node.name.contains("$")

    def addTypeParams(
        declaration: Declaration,
        ownerSymbol: String,
        grandOwner: String): Seq[Binding] = {
      // set formal parameters after the ownerSymbol has been computed.
      // method symbols
      declaration.setFormalTypeParameters(ownerSymbol, scopes, grandOwner)
      declaration.formalTypeParameters.map { tparam: JType =>
        val tparamName = tparam.name
        val tparamSymbol = tparam.symbol
        val tparamTpe = s.Type(
          s.Type.Tag.TYPE_TYPE,
          typeType = Some(
            s.TypeType(Nil, upperBound = tparam.interfaceBound.map(_.toType(ownerSymbol, scopes)))))
        buf += s.SymbolInformation(
          tparamSymbol,
          kind = k.TYPE_PARAMETER,
          name = tparamName,
          owner = classSymbol,
          tpe = Some(tparamTpe)
        )
        Binding(tparamName, tparamSymbol)
      }

    }

    def addPackage(name: String, owner: String): String = {
      val packageSymbol = owner + name + "."
      buf += s.SymbolInformation(
        symbol = packageSymbol,
        kind = k.PACKAGE,
        name = name,
        owner = owner
      )
      packageSymbol
    }
    val classOwner: String = if (isTopLevelClass) {
      // Emit packages
      val packages = node.name.split("/")
      packages.iterator
        .take(packages.length - 1)
        .foldLeft(addPackage("_root_", "")) {
          case (owner, name) => addPackage(name, owner)
        }
    } else {
      ssym(node.name.substring(0, node.name.length - className.length - 1))
    }

    def saccessibility(access: Int): Option[s.Accessibility] = {
      val a = s.Accessibility.Tag
      if (access.hasFlag(o.ACC_PUBLIC)) accessibility(a.PUBLIC)
      else if (access.hasFlag(o.ACC_PROTECTED)) accessibility(a.PROTECTED)
      else if (access.hasFlag(o.ACC_PRIVATE)) accessibility(a.PRIVATE)
      else
        Some(
          s.Accessibility(a.PRIVATE_WITHIN, classOwner.substring(0, classOwner.lastIndexOf('.'))))
    }

    val classKind =
      if (node.access.hasFlag(o.ACC_INTERFACE)) k.TRAIT
      else k.CLASS

    val classDeclaration =
      if (node.signature == null) None
      else {
        val signature = getSignature(className, node.signature, null)
        Some(signature)
      }
    val tparams = classDeclaration match {
      case None => Nil
      case Some(decl: Declaration) =>
        val bindings = addTypeParams(decl, classSymbol, classOwner)
        bindings.map(_.symbol)
    }

    val methods: Seq[MethodNode] = node.methods.asScala
    val descriptors: Seq[Declaration] = methods.map { m =>
      // TODO(olafur) pass in methodSymbol instead of classSymbol
      getSignature(m.name, m.signature, m.desc)
    }
    methods.zip(descriptors).foreach {
      case (method: MethodNode, declaration: Declaration) =>
        val finalDescriptor = {
          val conflicting = descriptors.filter { d =>
            d.descriptor == declaration.descriptor &&
            d.name == method.name
          }
          if (conflicting.lengthCompare(1) == 0) declaration.descriptor
          else {
            val index = conflicting.indexOf(declaration) + 1
            declaration.descriptor + "+" + index
          }
        }
        val methodSymbol = classSymbol + method.name + "(" + finalDescriptor + ")."
        val methodKind = k.DEF
        val paramSymbols = ListBuffer.empty[String]
        val tparams = addTypeParams(declaration, methodSymbol, classSymbol)
        val methodType = s.Type(
          s.Type.Tag.METHOD_TYPE,
          methodType = Some(
            s.MethodType(
              parameters = s.MethodType.ParameterList(paramSymbols) :: Nil,
              returnType = declaration.returnType.map(_.toType(methodSymbol, scopes))
            )
          )
        )
        declaration.parameterTypes.zipWithIndex.foreach {
          case (param: JType, i) =>
            // TODO(olafur) use node.parameters.name if -parameters is set in javacOptions
            val paramName = "arg" + i
            val paramSymbol = methodSymbol + "(" + paramName + ")"
            paramSymbols += paramSymbol
            val paramKind = k.PARAMETER
            val paramTpe = param.toType(methodSymbol, scopes)
            buf += s.SymbolInformation(
              symbol = paramSymbol,
              kind = paramKind,
              name = paramName,
              owner = methodSymbol,
              tpe = Some(paramTpe)
            )
        }

        val finalMethodType =
          if (declaration.formalTypeParameters.isEmpty) methodType
          else {
            s.Type(
              s.Type.Tag.UNIVERSAL_TYPE,
              universalType = Some(
                s.UniversalType(
                  tparams.map(_.symbol),
                  Some(methodType)
                ))
            )
          }

        buf += s.SymbolInformation(
          symbol = methodSymbol,
          kind = methodKind,
          name = method.name,
          owner = classSymbol,
          tpe = Some(finalMethodType),
          accessibility = saccessibility(method.access)
        )
    }

    node.fields.asScala.foreach { field: FieldNode =>
      val fieldSymbol = classSymbol + field.name + "."
      scopes.update(fieldSymbol, classSymbol, Nil)
      val fieldKind =
        if (field.access.hasFlag(o.ACC_FINAL)) k.VAL
        else k.VAR
      val declaration = getSignature(field.name, field.signature, field.desc, isField = true)
      buf += s.SymbolInformation(
        symbol = fieldSymbol,
        kind = fieldKind,
        name = field.name,
        owner = classSymbol,
        tpe = declaration.fieldType.map(_.toType(fieldSymbol, scopes)),
        accessibility = saccessibility(field.access)
      )
    }

    val decls = buf.map(_.symbol)
    val parents: Seq[s.Type] = classDeclaration match {
      case None =>
        (node.superName +: node.interfaces.asScala).map { parent =>
          val symbol = ssym(parent)
          s.Type(s.Type.Tag.TYPE_REF, typeRef = Some(s.TypeRef(symbol = symbol)))
        }
      case Some(decl: Declaration) =>
        decl.superClass(classSymbol, scopes) match {
          case Some(superClass) => superClass +: decl.interfaceTypes(classSymbol, scopes)
          case None => decl.interfaceTypes(classSymbol, scopes)
        }
    }
    val classTpe = s.Type(
      tag = s.Type.Tag.CLASS_INFO_TYPE,
      classInfoType = Some(
        s.ClassInfoType(
          typeParameters = tparams,
          parents = parents,
          declarations = decls
        )
      )
    )

    buf += s.SymbolInformation(
      symbol = classSymbol,
      kind = classKind,
      name = className,
      owner = classOwner,
      tpe = Some(classTpe),
      accessibility = saccessibility(node.access)
    )

    val uri = root.relativize(file).toString
    s.TextDocument(
      schema = s.Schema.SEMANTICDB3,
      uri = uri,
      symbols = buf
    )
  }

  def asmNodeFromBytes(bytes: Array[Byte]): ClassNode = {
    val node = new ClassNode()
    new ClassReader(bytes).accept(
      node,
      ClassReader.SKIP_DEBUG |
        ClassReader.SKIP_FRAMES |
        ClassReader.SKIP_CODE
    )
    node
  }

  def getName(symbol: String): String = {
    val dollar = symbol.lastIndexOf('$')
    if (dollar < 0) {
      val slash = symbol.lastIndexOf('/')
      if (slash < 0) sys.error(s"Missing $$ or / from symbol '$symbol'")
      else symbol.substring(slash + 1)
    } else {
      symbol.substring(dollar + 1)
    }
  }

  def ssym(string: String): String =
    "_root_." + string.replace('$', '#').replace('/', '.') + "#"

  implicit class XtensionAccess(n: Int) {
    def hasFlag(flag: Int): Boolean =
      (flag & n) != 0
  }

  def getSignature(
      name: String,
      signature: String,
      desc: String,
      isField: Boolean = false
  ): Declaration = {
    val toParse =
      if (signature != null) signature
      else if (desc != null) desc
      else sys.error("Null pointer exception!! " + name)
    val signatureReader = new SignatureReader(toParse)
    val v = new SemanticdbSignatureVisitor
    signatureReader.accept(v)
    // HACK(olafur) Fields have signature like "I" that trigger visitSuperClass,
    // which causes the mode to be SuperClass instead of Start (which I expected)
    // isField = true means we are computing a declaration for a field and can therefore
    // poke directly into `v.tpe`.
    val fieldType = if (isField) Some(v.tpe) else None
    Declaration(
      name = name,
      desc = toParse,
      formalTypeParameters = v.formalTypeParameters,
      parameterTypes = v.parameterTypes,
      returnType = v.returnType,
      fieldType = fieldType,
      mySuperClass = v.superClassType,
      myInterfaceTypes = v.interfaceTypes
    )
  }

  def accessibility(tag: s.Accessibility.Tag): Option[s.Accessibility] = Some(s.Accessibility(tag))

  sealed trait SignatureMode

  class JType(
      var isArray: Boolean,
      var isTypeVariable: Boolean,
      var symbol: String,
      var name: String,
      val args: ListBuffer[JType],
      var interfaceBound: Option[JType]
  ) {
    def setPrimitive(name: String): Unit = {
      this.symbol = s"_root_.scala.$name#"
      this.name = name
    }

    def setSymbol(newSymbol: String): Unit = {
      symbol = ssym(newSymbol)
      name = getName(newSymbol)
    }

    def toType(owner: String, scopes: Scopes): s.Type = {
      if (isTypeVariable) {
        this.symbol = scopes.resolve(name, owner)
      }
      val tpe = ref(symbol, args.iterator.map(_.toType(owner, scopes)).toList)
      if (isArray) array(tpe)
      else tpe
    }

    override def toString: String = {
      val suffix = if (isArray) "[]" else ""
      if (args.isEmpty) symbol + suffix
      else symbol + args.mkString("<", ", ", ">") + suffix
    }
  }

  class SemanticdbSignatureVisitor() extends SignatureVisitor(o.ASM5) {

    import SignatureMode._

    val parameterTypes: ListBuffer[JType] = ListBuffer.empty[JType]
    val formalTypeParameters: ListBuffer[JType] = ListBuffer.empty[JType]
    var owners = List.empty[JType]
    val interfaceTypes = ListBuffer.empty[JType]
    var superClassType = Option.empty[JType]
    def newTpe = new JType(false, false, "", "", ListBuffer.empty[JType], None)
    var tpe: JType = newTpe
    var mode: SignatureMode = Start

    var myReturnType = Option.empty[JType]
    def returnType: Option[JType] =
      myReturnType.orElse {
        if (mode == ReturnType) Some(tpe)
        else None
      }

    override def visitParameterType(): SignatureVisitor = {
//      pprint.log("Parameter type")
      mode = ParameterType
      tpe = newTpe
      parameterTypes += tpe
      this
    }

    override def visitClassType(name: String): Unit = {
//      pprint.log(name)
      tpe.setSymbol(name)
    }

    override def visitFormalTypeParameter(name: String): Unit = {
//      pprint.log(name)
      mode = FormalType
      formalTypeParameters += newTpe
      formalTypeParameters.last.name = name
    }

    override def visitTypeArgument(wildcard: Char): SignatureVisitor = {
//      pprint.log(wildcard)
      val arg = newTpe
      tpe.args += arg
      startType()
      tpe = arg
      this
    }

    def startType(): Unit = {
      owners = tpe :: owners
    }

    override def visitArrayType(): SignatureVisitor = {
      tpe.isArray = true
      this
    }

    override def visitTypeArgument(): Unit = {
//      pprint.log("Type Argument")
    }

    override def visitEnd(): Unit = {
//      pprint.log("END")
      endType()
      mode match {
        case Interface =>
          interfaceTypes += tpe
        case SuperClass =>
          superClassType = Some(tpe)
        case ReturnType =>
          myReturnType = Some(tpe)
        case InterfaceBound =>
          formalTypeParameters.last.interfaceBound = Some(tpe)
          tpe = newTpe
        case _ =>
      }
    }

    def endType(): Unit = {
      if (owners.nonEmpty) {
        tpe = owners.head
        owners = owners.tail
      }
    }

    override def visitTypeVariable(name: String): Unit = {
//      pprint.log(name)
      tpe.isTypeVariable = true
      tpe.name = name
    }

    override def visitExceptionType(): SignatureVisitor = {
//      pprint.log("exceptionType")
      this
    }

    def setMode(mode: SignatureMode): Unit = {
      this.mode = mode
      tpe = newTpe
    }

    override def visitSuperclass(): SignatureVisitor = {
//      pprint.log("superClass")
      setMode(SuperClass)
      this
    }

    override def visitInterface(): SignatureVisitor = {
//      pprint.log("Interface")
      setMode(Interface)
      this
    }

    override def visitReturnType(): SignatureVisitor = {
      setMode(ReturnType)
      this
    }

    override def visitInterfaceBound(): SignatureVisitor = {
      setMode(InterfaceBound)
//      pprint.log("interface bound")
      this
    }

    override def visitInnerClassType(name: String): Unit = {
//      pprint.log(name)
    }

    override def visitClassBound(): SignatureVisitor = {
//      pprint.log("classBound")
      this
    }

    override def visitBaseType(descriptor: Char): Unit = {
//      pprint.log(descriptor)
      descriptor match {
        case 'V' => tpe.setPrimitive("Unit")
        case 'B' => tpe.setPrimitive("Byte")
        case 'J' => tpe.setPrimitive("Long")
        case 'Z' => tpe.setPrimitive("Boolean")
        case 'I' => tpe.setPrimitive("Int")
        case 'S' => tpe.setPrimitive("Short")
        case 'C' => tpe.setPrimitive("Char")
        case 'F' => tpe.setPrimitive("Float")
        case 'D' => tpe.setPrimitive("Double")
        case _ => sys.error(descriptor.toString)
      }
    }

  }

  case class Declaration(
      name: String,
      desc: String,
      formalTypeParameters: Seq[JType],
      parameterTypes: Seq[JType],
      returnType: Option[JType],
      fieldType: Option[JType],
      mySuperClass: Option[JType],
      myInterfaceTypes: Seq[JType]
  ) {

    /** Set the symbols for formal type parameters.
      *
      * This needs to happen after we complete the declaration because we don't know the
      * owner symbol for methods until after we have computed types for all parameters
      * for all methods in this class.
      */
    def setFormalTypeParameters(owner: String, scopes: Scopes, grandOwner: String): Unit = {

      val bindings = formalTypeParameters.map { tparam: JType =>
//        pprint.log(tparam.name)
        tparam.symbol = owner + "[" + tparam.name + "]"
        Binding(tparam.name, tparam.symbol)
      }

      scopes.update(owner, grandOwner, bindings)
    }

    def superClass(owner: String, scopes: Scopes): Option[s.Type] =
      mySuperClass.map(_.toType(owner, scopes))
    def interfaceTypes(owner: String, scopes: Scopes): Seq[s.Type] =
      myInterfaceTypes.map(_.toType(owner, scopes))
    val descriptor = parameterTypes.map(_.name).mkString(",")
  }

  object SignatureMode {

    case object Start extends SignatureMode
    case object ParameterType extends SignatureMode
    case object SuperClass extends SignatureMode
    case object Interface extends SignatureMode
    case object FormalType extends SignatureMode
    case object ReturnType extends SignatureMode
    case object InterfaceBound extends SignatureMode

  }
}
