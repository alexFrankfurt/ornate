package com.novocode.mdoc.config

import java.net.URI

import com.novocode.mdoc.{Logging, Extension, Extensions}
import com.novocode.mdoc.theme.Theme
import org.commonmark.html.HtmlRenderer.HtmlRendererExtension
import org.commonmark.parser.Parser.ParserExtension

import scala.collection.JavaConverters._
import better.files._
import com.typesafe.config.{ConfigValue, ConfigFactory, Config}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class Global(startDir: File, confFile: File) extends Logging {
  val (referenceConfig: ReferenceConfig, userConfig: UserConfig) = {
    val ref = ConfigFactory.parseResources(getClass, "/mdoc-reference.conf")
    val refC = new ReferenceConfig(ref.resolve(), this)
    if(confFile.exists) {
      val c = ConfigFactory.parseFile(confFile.toJava)
      logger.info(s"Using configuration file $confFile")
      (refC, new UserConfig(c.withFallback(ref).resolve(), startDir, this))
    } else {
      logger.info(s"Configuration file $confFile not found, using defaults from reference.conf")
      (refC, new UserConfig(refC.raw, startDir, this))
    }
  }

  logger.debug("Source dir is: " + userConfig.sourceDir)
  logger.debug("Target dir is: " + userConfig.targetDir)

  private val cachedExtensions = new mutable.HashMap[String, Option[AnyRef]]
  private[config] def getCachedExtensionObject(className: String): Option[AnyRef] = cachedExtensions.getOrElseUpdate(className, {
    try {
      val cls = Class.forName(className)
      if(classOf[Extension].isAssignableFrom(cls))
        Some(cls.newInstance().asInstanceOf[Extension])
      else // CommonMark extension
        Some(cls.getMethod("create").invoke(null))
    } catch { case ex: Exception =>
      logger.error(s"Error instantiating extension class $className -- disabling extension", ex)
      None
    }
  })

  def createTheme: Theme = {
    val cl = userConfig.themeClass
    logger.debug(s"Creating theme from class $cl")
    Class.forName(cl).getConstructor(classOf[Global]).newInstance(this).asInstanceOf[Theme]
  }
}

/** Reference configuration, used for extra pages generated by themes */
class ReferenceConfig(val raw: Config, global: Global) {
  private[this] val (aliasToExtension: (String => String), extensionToAlias: (String => String)) = {
    val m = raw.getObject("global.extensionAliases").unwrapped().asScala.toMap.mapValues(_.toString)
    (m.withDefault(identity), m.map { case (a, e) => (e, a) }.withDefault(identity))
  }

  def getExtensions(names: Iterable[String]): Extensions = {
    val b = new ListBuffer[String]
    names.foreach { s =>
      if(s.startsWith("-")) b -= aliasToExtension(s.substring(1))
      else b += aliasToExtension(s)
    }
    val normalized = b.map(extensionToAlias).toVector
    new Extensions(normalized.map(a => (a, global.getCachedExtensionObject(aliasToExtension(a)))))
  }

  def parsePageConfig(hocon: String): Config =
    ConfigFactory.parseString(hocon).withFallback(raw).resolve()
}

/** User configuration */
class UserConfig(raw: Config, startDir: File, global: Global) extends ReferenceConfig(raw, global) {
  val sourceDir: File = startDir / raw.getString("global.srcdir")
  val targetDir: File = startDir / raw.getString("global.targetdir")
  val tocMaxLevel: Int = raw.getInt("global.tocMaxLevel")
  val tocMergeFirst: Boolean = raw.getBoolean("global.tocMergeFirst")

  private[this] val themeName = raw.getString("global.theme")

  val themeClass = {
    val aliasToTheme =
      raw.getObject("global.themeAliases").unwrapped().asScala.toMap.mapValues(_.toString).withDefault(identity)
    aliasToTheme(themeName)
  }

  val themeConfig: Config = {
    val n = s"theme.$themeName"
    if(raw.hasPath(n)) raw.getConfig(n)
    else ConfigFactory.empty()
  }

  val toc: Option[Vector[ConfigValue]] =
    if(raw.hasPath("global.toc")) Some(raw.getList("global.toc").asScala.toVector)
    else None
}