package com.sijunyang.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Repository policy only: compilers, dependency resolution and test execution remain Gradle-owned. */
class ModuleBoundariesPlugin implements Plugin<Project> {
    static final List<String> OWNERS = ['analysis-model', 'analysis-core', 'editor-ui', 'analysis-runtime', 'plugin']
    static final Map<String, Set<String>> ALLOWED = [
        'analysis-model': [] as Set,
        'analysis-core': ['analysis-model'] as Set,
        'editor-ui': ['analysis-model'] as Set,
        'analysis-runtime': ['analysis-model', 'analysis-core', 'editor-ui'] as Set,
        'plugin': ['analysis-model', 'editor-ui', 'analysis-runtime'] as Set,
    ]

    static Set dependencyClosure(Object task) {
        def seen = [] as Set
        def pending = [task]
        while (!pending.empty) {
            def current = pending.remove(pending.size() - 1)
            task.project.gradle.taskGraph.getDependencies(current).each { dependency ->
                if (seen.add(dependency)) pending.add(dependency)
            }
        }
        seen
    }

    static boolean pureEntry(Project root) {
        def directory = root.rootDir.canonicalFile
        root.name == 'bracket-guides-pure' && directory.name == 'pure-build' &&
            directory.parentFile.name == 'tools' &&
            root.findProject(':analysis-model')?.projectDir?.canonicalFile == new File(directory, '../../analysis-model').canonicalFile &&
            root.findProject(':analysis-core')?.projectDir?.canonicalFile == new File(directory, '../../analysis-core').canonicalFile
    }

    void apply(Project root) {
        def owners = OWNERS.collect { root.findProject(":${it}") }.findAll { it != null }
        def expectedOwners = pureEntry(root) ? ['analysis-model', 'analysis-core'] : OWNERS
        require(owners.collect { it.name }.toSet() == expectedOwners.toSet(), 'production owner set is incomplete')
        root.tasks.register('exportBenchmarkMetrics', BenchmarkReportTask) {
            inputFile.set(root.layout.file(root.providers.gradleProperty('benchmarkResults').map { root.file(it) }))
            outputFile.set(root.layout.file(root.providers.gradleProperty('benchmarkBmf').orElse('build/benchmark-metrics.json').map { root.file(it) }))
            job.set(root.providers.gradleProperty('benchmarkJob').orElse('all'))
        }
        owners.each { owner ->
            owner.tasks.withType(JavaCompile).matching { it.name == 'compileJava' || owner.name == 'editor-ui' && it.name == 'compileTestJava' }.configureEach {
                options.sourcepath = owner.files()
            }
        }
        root.tasks.register('verifyProductionModules') {
            group = 'verification'
            description = 'Checks actual compiler inputs and production source/output ownership.'
            dependsOn(owners.collect { "${it.path}:classes" })
            notCompatibleWithConfigurationCache('Audits resolved compiler tasks, including Kotlin compatibility adapter.')
            doLast { verify(root, owners) }
        }
        if (root.findProject(':plugin') != null) {
            root.tasks.register('verifyPluginPackaging') {
                group = 'verification'
                description = 'Checks packaged owner byte identity and resource/dependency ownership.'
                dependsOn(':plugin:buildPlugin', owners.collect { "${it.path}:jar" })
                notCompatibleWithConfigurationCache('Reads final owner archive task outputs.')
                doLast { verifyArchive(root, owners) }
            }
            root.tasks.named('check') { dependsOn('verifyPluginPackaging') }
        }
    }

    static void require(boolean condition, String why) {
        if (!condition) throw new GradleException("Module boundary violation: ${why}")
    }

    // Kotlin exposes effective source/friend/plugin inputs through version-mangled getters.
    // This single compatibility seam fails closed and is exercised with the pinned compiler.
    static Object kotlinInput(Object task, String getter) {
        def methods = task.class.methods.findAll {
            it.parameterCount == 0 && (it.name == getter || it.name.startsWith(getter + '$'))
        }
        require(methods.size() == 1, "effective Kotlin input ${getter} is unavailable/ambiguous")
        methods[0].invoke(task)
    }

    // The pinned KGP's legacy KotlinPluginData getter can be null even when a plugin
    // is loaded. Bind the effective -Xplugin input to resolved module identities AND
    // known bytes; filenames and applied plugin IDs are not evidence of isolation.
    static final Map<String, String> DEFAULT_COMPILER_ARTIFACTS = [
        'org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.3.21': 'a04ff06efb4a5ae07a928da94f9b5d061230c96d7febdaeb8bc7438e1aa31811',
        'org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:2.3.21': '89e78a3326e1205a9420a19441a4f382f7a72241d52c96425e89e5b130387286',
        'org.jetbrains.kotlin:kotlin-scripting-jvm:2.3.21': '8cb637132fc7fa511c700ee7f2b63b282630167f5ebd699a263a0b67b5d486ab',
        'org.jetbrains.kotlin:kotlin-scripting-common:2.3.21': '69885751bdce7d4e1d1b9758baf4bc0fe03b24827abd7b7687e5a7c0b4d029ed',
        'org.jetbrains.kotlin:kotlin-stdlib:2.3.21': '6f64eac736db9434dd6925b4a518b9d1d17177652320c37916cf9ba3ce7d7d7a',
        'org.jetbrains.kotlin:kotlin-script-runtime:2.3.21': 'ec61bf1229c837fa9f4255f34ca69816138b73158e7ae9b8d964cf92ca6dfd2e',
        'org.jetbrains:annotations:13.0': 'ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478',
    ]

    static void verifyCompilerPlugins(Project owner, Object compiler, String sourceSet, String reason, StringBuilder evidence) {
        def configuration = owner.configurations.findByName('kotlinCompilerPluginClasspath' + sourceSet)
        require(configuration != null && configuration.canBeResolved, reason + ': effective configuration unavailable')
        def artifacts = configuration.incoming.artifacts.artifacts
        def resolvedFiles = [] as Set
        artifacts.each { artifact ->
            def id = artifact.id.componentIdentifier
            require(id instanceof ModuleComponentIdentifier, reason + ': foreign artifact origin')
            def coordinate = "${id.group}:${id.module}:${id.version}".toString()
            def expectedHash = DEFAULT_COMPILER_ARTIFACTS[coordinate]
            require(expectedHash != null && digest(artifact.file.bytes) == expectedHash, reason + ': unapproved artifact ' + coordinate)
            resolvedFiles.add(artifact.file.canonicalFile)
        }
        require(configuration.files.collect { it.canonicalFile }.toSet() == resolvedFiles, reason + ': unowned artifact input')
        def arguments = kotlinInput(compiler, 'getSerializedCompilerArgumentsIgnoreClasspathIssues')
        require(arguments instanceof Collection, reason + ': effective arguments unavailable')
        def pluginArguments = arguments.findAll { it.startsWith('-Xplugin=') }
        def pluginFiles = pluginArguments.collectMany { it.substring('-Xplugin='.length()).split(',').collect { path -> new File(path).canonicalFile } }
        require(pluginFiles.size() == pluginFiles.toSet().size() && pluginFiles.toSet() == resolvedFiles, reason + ': effective plugin paths differ')
        require(!arguments.any { it == '-P' || it.startsWith('-P=') || it.startsWith('-Xcompiler-plugin') || it.startsWith('-Xplugin') && !it.startsWith('-Xplugin=') }, reason + ': plugin option injection')
        evidence.append("${owner.name}.${sourceSet}.effective-compiler-plugins=${pluginFiles.collect { it.path }.sort()}\n")
    }

    static Set<String> classNames(Collection<File> paths) {
        def names = [] as Set
        paths.each { path ->
            if (path.isDirectory()) {
                path.eachFileRecurse { f ->
                    if (f.isFile() && f.name.endsWith('.class')) names.add(path.toPath().relativize(f.toPath()).toString().replace('\\', '/'))
                }
            } else if (path.name.endsWith('.jar')) {
                new JarFile(path).withCloseable { jar ->
                    jar.entries().each { e -> if (e.name.endsWith('.class')) names.add(e.name) }
                }
            }
        }
        names
    }

    static boolean inside(File file, File root) {
        file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }

    static void verify(Project root, List<Project> owners) {
        def expectedOwners = pureEntry(root) ? ['analysis-model', 'analysis-core'] : OWNERS
        require(owners.collect { it.name }.toSet() == expectedOwners.toSet(), 'production owner set is incomplete')
        def outputs = owners.collectEntries { p -> [(p.name): p.sourceSets.main.output.classesDirs.files] }
        owners.each { a ->
            owners.findAll { it != a }.each { b ->
                outputs[a.name].each { left ->
                    outputs[b.name].each { right ->
                        require(!inside(left, right) && !inside(right, left), "shared owner outputs ${a.name}/${b.name}")
                    }
                }
            }
        }
        def ownerClasses = outputs.collectEntries { name, dirs -> [(name): classNames(dirs)] }
        def destinations = owners.collectMany { p ->
            [p.tasks.named('compileJava').get().destinationDirectory.get().asFile,
             p.tasks.named('compileKotlin').get().destinationDirectory.get().asFile]
        }
        destinations.eachWithIndex { a, i ->
            destinations.eachWithIndex { b, j ->
                if (i != j) require(!inside(a, b) && !inside(b, a), 'shared compiler destination')
            }
        }
        def evidence = new StringBuilder()
        owners.each { p ->
            def java = p.tasks.named('compileJava').get()
            def kotlin = p.tasks.named('compileKotlin').get()
            def projects = p.configurations.compileClasspath.incoming.resolutionResult.allComponents.findAll {
                it.id instanceof ProjectComponentIdentifier && it.id.projectPath != p.path
            }.collect { it.id.projectPath.substring(1) } as Set
            require(ALLOWED[p.name].containsAll(projects), "${p.name} has forbidden project dependencies ${projects - ALLOWED[p.name]}")
            require(java.options.sourcepath != null && java.options.sourcepath.empty, "${p.name} javac sourcepath must be explicitly empty")
            require(java.options.bootstrapClasspath == null || java.options.bootstrapClasspath.empty, "${p.name} javac bootstrap classpath")
            require(java.options.annotationProcessorPath == null || java.options.annotationProcessorPath.empty, "${p.name} javac annotation processor path")
            require(java.options.compilerArgs.empty, "${p.name} unsupported javac argument injection")
            def lateArguments = kotlinInput(kotlin, 'getExecutionTimeFreeCompilerArgs')
            require(lateArguments == null || lateArguments.empty, "${p.name} execution-time Kotlin argument injection")
            require(kotlin.compilerOptions.freeCompilerArgs.get().empty, "${p.name} unsupported Kotlin argument injection")
            def friends = kotlinInput(kotlin, 'getFriendPathsSet')
            require(friends != null && friends.get().empty, "${p.name} production friend paths")
            verifyCompilerPlugins(p, kotlin, 'Main', "${p.name} production compiler plugin injection", evidence)
            def commonSources = kotlinInput(kotlin, 'getCommonSourceSet')
            def scripts = kotlinInput(kotlin, 'getScriptSources')
            require(scripts.empty, "${p.name} production script source injection")
            def sources = java.source.files + kotlin.javaSources.files + kotlinInput(kotlin, 'getSourceFiles').files + commonSources.files
            require(sources.every { inside(it, new File(p.projectDir, 'src/main')) }, "${p.name} foreign compiler source")
            p.sourceSets.main.allSource.srcDirs.each { src ->
                require(inside(src, new File(p.projectDir, 'src/main')), "${p.name} shared source root ${src}")
            }
            [java.classpath.files, kotlin.libraries.files].eachWithIndex { cp, i ->
                def visible = classNames(cp)
                (OWNERS - ALLOWED[p.name] - [p.name]).each { forbidden ->
                    require((visible.intersect(ownerClasses[forbidden] ?: [] as Set)).empty, "${p.name} compiler ${i} sees ${forbidden} bytecode")
                    (outputs[forbidden] ?: []).each { out ->
                        require(cp.every { !inside(it, out) && !inside(out, it) }, "${p.name} compiler ${i} shares ${forbidden} output")
                    }
                }
                if (p.name in ['analysis-model', 'analysis-core']) {
                    require(!visible.any { it.startsWith('com/intellij/') }, "${p.name} sees IntelliJ SDK bytecode")
                }
                cp.findAll { it.isFile() && it.name.endsWith('.jar') }.each { path ->
                    new JarFile(path).withCloseable { jar ->
                        jar.entries().findAll { it.name.endsWith('.java') }.each { entry ->
                            def stem = entry.name.substring(0, entry.name.length() - 5)
                            def source = jar.getInputStream(entry).getText('UTF-8')
                            def declaration = source =~ /(?m)^\s*package\s+([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)*)\s*;/
                            def packagePath = declaration.find() ? declaration.group(1).replace('.', '/') + '/' : ''
                            def simpleName = stem.substring(stem.lastIndexOf('/') + 1)
                            def compilablePath = entry.name == packagePath + simpleName + '.java'
                            require(!compilablePath || jar.getEntry(stem + '.class') != null, "${p.name} source-only compiler archive entry ${entry.name}")
                        }
                    }
                }
                evidence.append("${p.name}.${i == 0 ? 'java' : 'kotlin'}\n")
                cp.each { evidence.append(it.canonicalPath).append('\n') }
            }
            // UI test compilation must respect the same implementation boundary.
            if (p.name == 'editor-ui') {
                def testJava = p.tasks.named('compileTestJava').get()
                def testKotlin = p.tasks.named('compileTestKotlin').get()
                def testInputs = [p.configurations.testCompileClasspath.files, testJava.classpath.files, testKotlin.libraries.files]
                testInputs.eachWithIndex { cp, i ->
                    def testVisible = classNames(cp)
                    ['analysis-core', 'analysis-runtime'].each { forbidden ->
                        require(testVisible.intersect(ownerClasses[forbidden] ?: [] as Set).empty, 'UI test classpath sees ' + forbidden)
                        (outputs[forbidden] ?: []).each { out -> require(cp.every { !inside(it, out) && !inside(out, it) }, 'UI test compiler shares forbidden output') }
                    }
                    evidence.append("editor-ui.test-compiler-${i}\n")
                    cp.each { evidence.append(it.canonicalPath).append('\n') }
                }
                require(testJava.options.sourcepath != null && testJava.options.sourcepath.empty, 'UI test javac sourcepath must be explicitly empty')
                require(testJava.options.compilerArgs.empty && (testJava.options.bootstrapClasspath == null || testJava.options.bootstrapClasspath.empty) &&
                    (testJava.options.annotationProcessorPath == null || testJava.options.annotationProcessorPath.empty), 'UI test Java compiler injection')
                require(testKotlin.compilerOptions.freeCompilerArgs.get().empty, 'UI test Kotlin compiler argument injection')
                def late = kotlinInput(testKotlin, 'getExecutionTimeFreeCompilerArgs')
                require(late == null || late.empty, 'UI test execution-time Kotlin argument injection')
                verifyCompilerPlugins(p, testKotlin, 'Test', 'UI test compiler plugin injection', evidence)
                require(kotlinInput(testKotlin, 'getScriptSources').empty, 'UI test script source injection')
                def testFriends = kotlinInput(testKotlin, 'getFriendPathsSet').get()
                def ownFriends = outputs['editor-ui'].collect { it.canonicalFile } as Set
                ownFriends.add(p.tasks.named('jar').get().archiveFile.get().asFile.canonicalFile)
                def normalizedFriends = testFriends.collect { friend ->
                    def file = new File(friend.toString())
                    (file.isAbsolute() ? file : new File(p.layout.buildDirectory.get().asFile, friend.toString())).canonicalFile
                }
                require(normalizedFriends.every { ownFriends.contains(it) }, 'UI test foreign friend path')
                evidence.append("editor-ui.test-friend-inputs=${normalizedFriends.collect { it.path }.sort()}\n")
                def testSources = testJava.source.files + testKotlin.javaSources.files + kotlinInput(testKotlin, 'getSourceFiles').files + kotlinInput(testKotlin, 'getCommonSourceSet').files
                require(testSources.every { inside(it, new File(p.projectDir, 'src/test')) }, 'UI test foreign compiler source')

            }
        }
        def report = new File(root.layout.buildDirectory.get().asFile, 'module-verification/compiler-inputs.txt')
        report.parentFile.mkdirs(); report.text = evidence.toString()
        root.logger.lifecycle('Actual production compiler ownership/classpaths verified.')
    }

    static String digest(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }

    static void verifyArchive(Project root, List<Project> owners) {
        require(owners.collect { it.name }.toSet() == OWNERS.toSet(), 'release must include exactly five production owners')
        def expected = [:]
        def ownerArchives = [:]
        def ownerInputs = new StringBuilder()
        // The platform plugin packages composedJar, not its raw/base jar.
        // Bind that identity to the actual prepareSandbox pluginJar provider.
        owners.each { p ->
            def jarTask = p.tasks.findByName('composedJar') ?: p.tasks.named('jar').get()
            def file = jarTask.archiveFile.get().asFile
            if (p.tasks.findByName('prepareSandbox') != null) {
                def sandbox = p.tasks.named('prepareSandbox').get()
                require(sandbox.pluginJar.get().asFile.canonicalFile == file.canonicalFile, 'sandbox selected a different owner packaging input')
                def dependencies = dependencyClosure(sandbox)
                ownerInputs.append("ACTUAL_SANDBOX_GRAPH_DEPENDENCY_CLOSURE=${dependencies.collect { it.path }.sort().join(',')}\n")
                require(dependencies.contains(jarTask), 'sandbox does not transitively depend on selected owner archive')
            }
            require(!ownerArchives.containsKey(file.name), 'duplicate owner archive name ' + file.name)
            ownerArchives[file.name] = digest(file.bytes)
            ownerInputs.append("OWNER=${p.name};JAR=${file.canonicalPath};SHA256=${digest(file.bytes)}\n")
            jarTask.source.files.findAll { it.name.endsWith('.class') }.each { ownerInputs.append("PACKAGING_INPUT=${it.canonicalPath}\n") }
            jarTask.taskDependencies.getDependencies(jarTask).each { ownerInputs.append("PACKAGING_DEPENDENCY=${it.path}\n") }
            def inputClasses = [:]
            // Instrumented output takes precedence for every class it actually emits.
            def classRoots = p.sourceSets.main.output.classesDirs.files.toList()
            p.tasks.matching { it.name == 'instrumentCode' }.each { instrumentation ->
                classRoots.add(instrumentation.outputDirectory.get().asFile)
            }
            classRoots.each { classRoot ->
                if (classRoot.exists()) p.fileTree(classRoot).matching { include('**/*.class') }.files.each { source ->
                    def className = classRoot.canonicalFile.toPath().relativize(source.canonicalFile.toPath()).toString().replace('\\', '/')
                    inputClasses[className] = digest(source.bytes)
                    ownerInputs.append("COMPILED_INPUT=${source.canonicalPath};SHA256=${digest(source.bytes)}\n")
                }
            }
            def archiveClasses = [] as Set
            new JarFile(file).withCloseable { jar ->
                jar.entries().findAll { it.name.endsWith('.class') }.each { e ->
                    require(!expected.containsKey(e.name), 'duplicate owner class ' + e.name)
                    def sha = digest(jar.getInputStream(e).bytes)
                    require(inputClasses[e.name] == sha, 'owner bytecode is not a compiled/instrumented input ' + e.name)
                    expected[e.name] = sha
                    archiveClasses.add(e.name)
                }
            }
            require(archiveClasses == inputClasses.keySet(), 'owner archive class set differs from packaging inputs')
        }
        require(!expected.empty, 'no production owner classes')
        def archive = root.project(':plugin').tasks.named('buildPlugin').get().archiveFile.get().asFile
        def seen = [:]; def resources = [] as Set
        def archivedJars = [] as Set
        def descriptor = null
        new ZipFile(archive).withCloseable { zip ->
            zip.entries().findAll { it.name.endsWith('.jar') }.each { e ->
                require(e.name ==~ /[^\/]+\/lib\/[^\/]+\.jar/, 'unexpected nested runtime jar ' + e.name)
                def name = e.name.substring(e.name.lastIndexOf('/') + 1)
                require(ownerArchives.containsKey(name) && archivedJars.add(name), 'unknown/duplicate runtime jar ' + name)
                def jarBytes = zip.getInputStream(e).bytes
                require(digest(jarBytes) == ownerArchives[name], 'release jar is not actual owner packaging input ' + name)
                def stream = new java.util.jar.JarInputStream(new ByteArrayInputStream(jarBytes))
                stream.withCloseable {
                    def entry
                    while ((entry = stream.nextJarEntry) != null) {
                        resources.add(entry.name)
                        if (entry.name == 'META-INF/plugin.xml') descriptor = stream.readAllBytes()
                        if (entry.name.endsWith('.class')) {
                            require(!seen.containsKey(entry.name), 'duplicate packaged class ' + entry.name)
                            seen[entry.name] = digest(stream.readAllBytes())
                            require(!entry.name.startsWith('com/intellij/') && !entry.name.startsWith('kotlin/') && !entry.name.startsWith('kotlinx/coroutines/'), 'bundled platform runtime ' + entry.name)
                            require(!entry.name.contains('/testing/') && !entry.name.contains('/visual/'), 'test bridge in release ' + entry.name)
                        }
                    }
                }
            }
        }
        require(archivedJars == ownerArchives.keySet(), 'missing production owner jar')
        require(seen.keySet() == expected.keySet(), 'unknown/missing packaged class')
        expected.each { name, sha -> require(seen[name] == sha, 'missing/stale packaged owner bytecode ' + name) }
        require(descriptor != null, 'missing plugin.xml')
        require(resources.contains('META-INF/pluginIcon.svg') && resources.contains('META-INF/pluginIcon_dark.svg'), 'missing plugin icons')
        def pluginXml = new groovy.xml.XmlSlurper(false, false).parse(new ByteArrayInputStream(descriptor))
        require(pluginXml.id.text() == 'com.sijunyang.bracketpairguides', 'wrong plugin id')
        require(pluginXml.'idea-version'.@'since-build'.text() == '241', 'minimum supported platform changed')
        def registrations = pluginXml.depthFirst().findAll { node ->
            node.attributes().any { key, value -> key in ['implementation', 'serviceImplementation', 'instance'] && value.toString().startsWith('com.sijunyang.bracketpairguides.') }
        }
        require(!registrations.empty, 'missing plugin registrations')
        registrations.each { node ->
            node.attributes().findAll { key, value -> key in ['implementation', 'serviceImplementation', 'instance'] }.each { key, value ->
                require(seen.containsKey(value.toString().replace('.', '/') + '.class'), 'missing registered implementation ' + value)
            }
        }
        root.file('licenses').eachFileRecurse { f ->
            if (f.isFile()) require(resources.contains('META-INF/licenses/' + root.file('licenses').toPath().relativize(f.toPath()).toString().replace('\\', '/')), 'missing attribution ' + f.name)
        }
        def evidence = new File(root.layout.buildDirectory.get().asFile, 'module-verification/archive-identity.txt')
        evidence.parentFile.mkdirs(); evidence.text = "SHA256=${digest(archive.bytes)}\nOWNER_CLASSES=${expected.size()}\nPACKAGED_CLASSES=${seen.size()}\n" + ownerInputs.toString()
    }
}
