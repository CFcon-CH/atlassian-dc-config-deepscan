import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

// The parse check stops at CONVERSION, which is syntax only. Variable scope is decided
// later, in semantic analysis: a closure parameter that shadows one of the endpoint
// closure's own (queryParams, body) parses cleanly and is refused by ScriptRunner. This
// runs semantic analysis without the platform classpath, so an unresolved Jira or
// Confluence class is expected and ignored; every other error fails the check.
if (args.length == 0) {
    throw new IllegalArgumentException('Usage: scopecheck.groovy <script> [<script> ...]')
}
int failures = 0
for (String path : args) {
    CompilerConfiguration config = new CompilerConfiguration()
    config.sourceEncoding = 'UTF-8'
    config.tolerance = 100000
    CompilationUnit unit = new CompilationUnit(config, null, new GroovyClassLoader(getClass().classLoader))
    unit.addSource(new File(path))
    try {
        unit.compile(Phases.SEMANTIC_ANALYSIS)
    } catch (CompilationFailedException ignored) {
        // The errors are read from the collector below.
    }
    List<String> problems = []
    for (Object message : unit.errorCollector.errors ?: []) {
        String text = message instanceof SyntaxErrorMessage ? ((SyntaxErrorMessage) message).cause.message : String.valueOf(message)
        if (!text.toLowerCase(Locale.ROOT).contains('unable to resolve class')) {
            problems.add(text.readLines().first())
        }
    }
    if (problems.isEmpty()) {
        println 'SCOPE OK  ' + path
    } else {
        failures += problems.size()
        println 'SCOPE ERRORS  ' + path
        problems.each { println '  ' + it }
    }
}
if (failures > 0) {
    System.exit(1)
}
