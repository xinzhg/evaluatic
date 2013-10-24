package com.github.safrain.evaluatic.groovy;

import com.github.safrain.evaluatic.Engine;
import com.github.safrain.evaluatic.EngineFactory;
import com.github.safrain.evaluatic.SourceCode;
import com.github.safrain.evaluatic.repository.SourceCodeRepository;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.jsr223.GroovyCompiledScript;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author safrain
 */
public class GroovyEngineFactory implements EngineFactory {
    static final String GROOVY_EXTENSION = ".groovy";
    private final Object lock = new Object();

    private Map<String, Cache> compiledCache = new HashMap<String, Cache>();
    private GroovyClassLoader groovyClassLoader = new GroovyClassLoader();

    public void clearCache() {
        compiledCache = new HashMap<String, Cache>();
        groovyClassLoader = new GroovyClassLoader();
    }

    @Override
    public Engine createEngine() {
        return new GroovyEngine();
    }


    public void reloadLibrary(SourceCodeRepository sourceCodeRepository) {
        for (SourceCode sourceCode : sourceCodeRepository.list()) {
            if (sourceCode.getName().endsWith(".lib.groovy")) {
                compile(sourceCode);
            }
        }
    }

    private Class<?> compile(SourceCode sourceCode) {
        String name = sourceCode.getName();
        Cache cache = compiledCache.get(name);
        if (cache == null || cache.sourceCode.getLastModified() < sourceCode.getLastModified()) {
            synchronized (lock) {
                //Discard compiled class
                compiledCache.remove(name);
                //Check extension
                if (name.length() <= GROOVY_EXTENSION.length() || !name.endsWith(GROOVY_EXTENSION)) {
                    throw new RuntimeException("Not a groovy source file");
                }
                //Extract class name
                String chopped = name.substring(0, name.length() - GROOVY_EXTENSION.length());
                String codeBase;
                String className;
                int lastIndex = chopped.lastIndexOf('/');
                if (lastIndex == -1) {
                    codeBase = "/";
                    className = chopped;
                } else {
                    codeBase = "/" + chopped.substring(0, lastIndex);
                    className = chopped.substring(lastIndex + 1);
                }

                GroovyCodeSource codeSource = new GroovyCodeSource(sourceCode.getSource(), className, codeBase);
                codeSource.setCachable(false);

                Class<?> parsedClass;

                try {
                    parsedClass = groovyClassLoader.parseClass(codeSource);
                } catch (CompilationFailedException e) {
                    throw new RuntimeException(e);
                }

                //Must specify package if script file is in a directory
                if (parsedClass.getPackage() == null) {
                    if (!codeBase.equals("/")) {
                        throw new RuntimeException("Package declaration not found in " + name);
                    }
                }

                cache = new Cache();
                cache.sourceCode = sourceCode;
                cache.compiledClass = parsedClass;
                compiledCache.put(name, cache);
                return parsedClass;
            }
        } else {
            return cache.compiledClass;
        }
    }

    public class GroovyEngine implements Engine {
        GroovyScriptEngineImpl groovyScriptEngine = new GroovyScriptEngineImpl(groovyClassLoader);

        @Override
        public Object evaluate(SourceCode sourceCode) {
            GroovyCompiledScript compiled = new GroovyCompiledScript(groovyScriptEngine, compile(sourceCode));
            try {
                return compiled.eval(groovyScriptEngine.getContext());
            } catch (ScriptException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Cache {
        SourceCode sourceCode;
        Class<?> compiledClass;
    }

}