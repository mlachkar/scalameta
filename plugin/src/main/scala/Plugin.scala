package scala.reflect.internal.hosts
package scalacompiler

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.{Plugin => NscPlugin, PluginComponent => NscPluginComponent}
import parser.HijackSyntaxAnalyzer
import macros.HijackAnalyzer
import macros.{MacroPlugin => PalladiumMacroPlugin}
import macros.RenumberPhase
import persistence.PersistencePhase

class Plugin(val global: Global) extends NscPlugin
                                    with HijackSyntaxAnalyzer
                                    with HijackAnalyzer
                                    with PalladiumMacroPlugin
                                    with RenumberPhase
                                    with PersistencePhase
                                    with PluginSettings {
  val name = "scalahost"
  val description = """Hosts Project Palladium in scalac.
  For more information visit https://github.com/scalareflect/scalahost"""
  val components = List[NscPluginComponent](RenumberComponent, PersistenceComponent)
  hijackSyntaxAnalyzer()
  hijackAnalyzer()
  global.analyzer.addMacroPlugin(palladiumMacroPlugin)
}
