package sbt.compiler

import xsbti.{Severity, Position, AnalysisCallback}
import xsbti.compile.ExtendedCompileProgress
import java.io.File
import xsbti.api.SourceAPI

class AnalysisCallbackAdapter(progress: ExtendedCompileProgress, delegate: AnalysisCallback) extends xsbti.AnalysisCallback{

  def beginSource(source: File) =
    delegate.beginSource(source)

  def problem(what: String, pos: Position, msg: String, severity: Severity, reported: Boolean) =
    delegate.problem(what, pos, msg, severity, reported)

  def api(sourceFile: File, source: SourceAPI) =
    delegate.api(sourceFile, source)

  def endSource(sourcePath: File) =
    delegate.endSource(sourcePath)

  def generatedClass(source: File, module: File, name: String) = {
    // NON BOILERPLATE
    progress.generated(source, module, name)

    delegate.generatedClass(source, module, name)
  }

  def binaryDependency(binary: File, name: String, source: File, publicInherited: Boolean) =
    delegate.binaryDependency(binary, name, source, publicInherited)

  def sourceDependency(dependsOn: File, source: File, publicInherited: Boolean) =
    delegate.sourceDependency(dependsOn, source, publicInherited)
}
