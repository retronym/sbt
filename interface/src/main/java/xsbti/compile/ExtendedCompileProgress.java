package xsbti.compile;

import java.io.File;

public interface ExtendedCompileProgress extends CompileProgress {
    void generated(File source, File module, String name);

    void deleted(File module);
}
