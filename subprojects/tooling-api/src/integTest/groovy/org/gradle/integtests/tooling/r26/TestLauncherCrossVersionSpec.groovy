/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.tooling.r26

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.apache.commons.io.output.TeeOutputStream
import org.gradle.api.GradleException
import org.gradle.integtests.tooling.fixture.*
import org.gradle.integtests.tooling.fixture.GradleBuildCancellation
import org.gradle.test.fixtures.ConcurrentTestUtil
import org.gradle.tooling.*
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.test.TestOperationDescriptor
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import org.junit.Rule

@ToolingApiVersion(">=2.6")
@TargetGradleVersion(">=2.6")
class TestLauncherCrossVersionSpec extends ToolingApiSpecification {
    TestOutputStream stderr = new TestOutputStream()
    TestOutputStream stdout = new TestOutputStream()

    ProgressEvents events = new ProgressEvents()

    @Rule
    GradleBuildCancellation cancellationTokenSource

    def setup() {
        testCode()
    }

    def "test launcher api fires progress events"() {
        given:
        collectDescriptorsFromBuild()
        when:
        launchTests(testDescriptors("example.MyTest"));
        then:
        events.assertIsABuild()
        events.operation("Task :compileJava").successful
        events.operation("Task :processResources").successful
        events.operation("Task :classes").successful
        events.operation("Task :compileTestJava").successful
        events.operation("Task :processTestResources").successful
        events.operation("Task :testClasses").successful
        events.operation("Task :test").successful
        events.operation("Task :secondTest").successful

        events.operation("Gradle Test Run :test").successful
        events.operation("Gradle Test Executor 1").successful
        events.operation("Gradle Test Run :secondTest").successful
        events.operation("Gradle Test Executor 2").successful
        events.tests.findAll { it.descriptor.displayName == "Test class example.MyTest" }.size() == 2
        events.tests.findAll { it.descriptor.displayName == "Test foo(example.MyTest)" }.size() == 2
        events.tests.findAll { it.descriptor.displayName == "Test foo2(example.MyTest)" }.size() == 2
        events.tests.findAll { it.descriptor.displayName == "Test class example2.MyOtherTest" }.size() == 2
        events.tests.size() == 12
    }

    def "can run specific test class passed via test descriptor"() {
        given:
        collectDescriptorsFromBuild()
        when:
        launchTests(testDescriptors("example.MyTest"));
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":secondTest")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: null) // TODO clarify if this is by design
        events.tests.size() == 12

        assertTestNotExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":test")
        assertTestNotExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":secondTest")
    }

    def "can run specific test method passed via test descriptor"() {
        given:
        collectDescriptorsFromBuild()
        when:
        launchTests(testDescriptors("example.MyTest", "foo"));
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":secondTest")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        events.tests.size() == 10

        assertTestNotExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTestNotExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
    }

    def "runs only test task linked in test descriptor"() {
        given:
        collectDescriptorsFromBuild()
        when:
        launchTests(testDescriptors("example.MyTest", null, ":secondTest"));
        then:
        assertTaskExecuted(":secondTest")
        assertTaskNotExecuted(":test")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        events.tests.size() == 6

        assertTestNotExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestNotExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
    }

    def "tests can be executed multiple times without task being up-to-date"() {
        given:
        collectDescriptorsFromBuild()
        and:
        launchTests(testDescriptors("example.MyTest", null, ":secondTest"))
        when:
        launchTests(testDescriptors("example.MyTest", null, ":secondTest"));
        then:
        assertTaskNotUpToDate(":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTaskNotExecuted(":test")
    }

    @Requires(TestPrecondition.JDK7_OR_LATER)
    def "can run and cancel testlauncher in continuous mode"() {
        given:
        collectDescriptorsFromBuild()
        when:
        withConnection { connection ->
            withCancellation { cancellationToken ->
                launchTests(connection, new TestResultHandler(), cancellationToken) { TestLauncher launcher ->
                    def testsToLaunch = testDescriptors("example.MyTest", null, ":secondTest")
                    launcher
                        .withTests(testsToLaunch.toArray(new TestOperationDescriptor[testsToLaunch.size()]))
                        .withArguments("-t")
                }

                waitingForBuild()
                assertTaskExecuted(":secondTest")
                assertTaskNotExecuted(":test")
                assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
                assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
                assertTestNotExecuted(className: "example.MyTest", methodName: "foo3", task: ":secondTest")
                assertTestNotExecuted(className: "example.MyTest", methodName: "foo4", task: ":secondTest")
                assert events.tests.size() == 6
                events.clear()
                changeTestSource()
                waitingForBuild()
            }
        }

        then:
        assertBuildCancelled()
        assertTaskExecuted(":secondTest")
        assertTaskNotExecuted(":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo3", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo4", task: ":secondTest")
        events.tests.size() == 8
    }

    public <T> T withCancellation(@ClosureParams(value = SimpleType, options = ["org.gradle.tooling.CancellationToken"]) Closure<T> cl) {
        return cancellationTokenSource.withCancellation(cl)
    }

    def "listener errors are rethrown on client side"() {
        given:
        collectDescriptorsFromBuild()
        def descriptors = testDescriptors("example.MyTest")
        def failingProgressListener = failingProgressListener()
        when:
        withConnection { ProjectConnection connection ->
            def testLauncher = connection.newTestLauncher()
            testLauncher.addProgressListener(failingProgressListener)
            testLauncher.withTests(descriptors.toArray(new TestOperationDescriptor[descriptors.size()]))
            testLauncher.run()
        };
        then:
        def e = thrown(ListenerFailedException)
        e.cause.message == "failing progress listener"
    }

    def "fails with meaningful error when no tests declared"() {
        when:
        launchTests([])
        then:
        def e = thrown(TestExecutionException)
        e.message == "No test declared for execution."
    }

    def "fails with meaningful error when declared class has not tests"() {
        given:
        file("src/test/java/util/TestUtil.java") << """
            package util;
            public class TestUtil {
                static void someUtilsMethod(){}
            }
        """
        when:
        launchTests { TestLauncher launcher ->
            launcher.withJvmTestClasses("util.TestUtil")
        }
        then:
        def e = thrown(TestExecutionException)
        e.cause.message == "Tests configured in TestLauncher not found in any candidate test task."
    }

    def "fails with meaningful error when test no longer exists"() {
        given:
        collectDescriptorsFromBuild()
        and:
        testClassRemoved()
        when:
        launchTests(testDescriptors("example.MyTest", null, ":test"));
        then:
        assertTaskExecuted(":test")
        assertTaskNotExecuted(":secondTest")

        def e = thrown(TestExecutionException)
        e.cause.message == "No tests found for given includes: [example.MyTest.*]"
    }

    def "build succeeds if test class is only available in one test task"() {
        given:
        file("src/moreTests/java/more/MoreTest.java") << """
            package more;
            public class MoreTest {
                @org.junit.Test public void bar() throws Exception {
                     org.junit.Assert.assertEquals(2, 2);
                }
            }
        """
        when:
        launchTests { TestLauncher launcher ->
            launcher.withJvmTestClasses("more.MoreTest")
        }
        then:
        assertTaskExecuted(":secondTest")
        assertTaskExecuted(":test")
    }

    def "fails with meaningful error when test class not available for any test task"() {
        when:
        withConnection { ProjectConnection connection ->
            def testLauncher = connection.newTestLauncher()
            testLauncher.withJvmTestClasses("org.acme.NotExistingTestClass")
            testLauncher.run()
        };
        then:
        assertTaskNotExecuted(":test")
        assertTaskNotExecuted(":secondTest")

        def e = thrown(TestExecutionException)
        e.cause.message == "Tests configured in TestLauncher not found in any candidate test task."
    }

    def "fails with meaningful error when test task no longer exists"() {
        given:
        collectDescriptorsFromBuild()
        and:
        buildFile.text = simpleJavaProject()
        when:
        launchTests(testDescriptors("example.MyTest", null, ":secondTest"));
        then:
        assertTaskNotExecuted(":secondTest")
        assertTaskNotExecuted(":test")

        def e = thrown(TestExecutionException)
        e.cause.message == "Requested test task with path ':secondTest' cannot be found."
    }

    def "fails with meaningful error when passing invalid arguments"() {
        when:
        launchTests { TestLauncher launcher ->
            launcher.withJvmTestClasses("example.MyTest")
                .withArguments("--someInvalidArgument")
        }
        then:
        def e = thrown(UnsupportedBuildArgumentException)
        e.message.contains("Unknown command-line option '--someInvalidArgument'.")
    }

    def "fails with BuildException when build fails"() {
        given:
        buildFile << "some invalid build code"
        when:
        launchTests { TestLauncher launcher ->
            launcher.withJvmTestClasses("example.MyTest")
        }
        then:
        thrown(BuildException)
    }

    def "throws BuildCancelledException when build canceled"() {
        given:
        buildFile << "some invalid build code"
        when:
        launchTests { TestLauncher launcher ->
            launcher.withJvmTestClasses("example.MyTest")
            launcher.withCancellationToken(cancellationTokenSource.token())
            cancellationTokenSource.cancel()
        }
        then:
        thrown(BuildCancelledException)
    }

    def "can execute test class passed by name"() {
        when:
        launchTests { TestLauncher testLauncher ->
            testLauncher.withJvmTestClasses("example.MyTest")
        }
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":secondTest")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        events.tests.size() == 12

        assertTestNotExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":test")
        assertTestNotExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":secondTest")
    }

    def "can execute multiple test classes passed by name"() {
        setup: "add testcase that should not be exeucted"
        file("src/test/java/example/MyFailingTest.java") << """
            package example;
            public class MyFailingTest {
                @org.junit.Test public void failing1() throws Exception {
                     org.junit.Assert.assertEquals(1, 2);
                }
            }
        """

        when:
        launchTests { TestLauncher testLauncher ->
            testLauncher.withJvmTestClasses("example.MyTest")
            testLauncher.withJvmTestClasses("example2.MyOtherTest")
        }
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":secondTest")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":test")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":secondTest")
        events.tests.size() == 16

        assertTestNotExecuted(className: "example.MyFailingTest", methodName: "failing1", task: ":test")
        assertTestNotExecuted(className: "example.MyFailingTest", methodName: "failing1", task: ":secondTest")
    }

    def "runs all test tasks in multi project build when test class passed by name"() {
        setup:
        settingsFile << "include ':sub1', 'sub2', ':sub2:sub3', ':sub4'"
        ["sub1", "sub2/sub3"].each { projectFolderName ->
            file("${projectFolderName}/src/test/java/example/MyTest.java") << """
                package example;
                public class MyTest {
                    @org.junit.Test public void foo() throws Exception {
                         org.junit.Assert.assertEquals(1, 1);
                    }
                }
            """
        }

        file("sub2/src/test/java/example2/MyOtherTest.java") << """
            package example2;
            public class MyOtherTest {
                @org.junit.Test public void bar() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
            }
            """
        when:
        launchTests { TestLauncher testLauncher ->
            testLauncher.withJvmTestClasses("example.MyTest")
            testLauncher.withJvmTestClasses("example2.MyOtherTest")
        }
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":secondTest")
        assertTaskExecuted(":sub1:test")
        assertTaskExecuted(":sub2:test")
        assertTaskExecuted(":sub2:sub3:test")
        assertTaskExecuted(":sub4:test")

        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo2", task: ":secondTest")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":test")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":secondTest")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":sub1:test")
        assertTestExecuted(className: "example2.MyOtherTest", methodName: "bar", task: ":sub2:test")
        assertTestExecuted(className: "example.MyTest", methodName: "foo", task: ":sub2:sub3:test")
        events.tests.size() == 10 + 7 + 9
    }

    def "compatible with configure on demand"() {
        setup:
        10.times {
            settingsFile << "include ':sub$it'\n"
            file("sub$it/src/test/java/example/MyTest.java") << """
                package example;
                public class MyTest {
                    @org.junit.Test public void foo() throws Exception {
                         org.junit.Assert.assertEquals(1, 1);
                    }
                }
            """
        }
        when:
        launchTests { TestLauncher testLauncher ->
            testLauncher.withArguments("--configure-on-demand")
            testLauncher.withJvmTestClasses("example.MyTest")
        }
        then:
        assertTaskExecuted(":test")
        assertTaskExecuted(":sub0:test")
        assertTaskExecuted(":sub1:test")
        assertTaskExecuted(":sub2:test")
        assertTaskExecuted(":sub3:test")
        assertTaskExecuted(":sub4:test")
        assertTaskExecuted(":sub5:test")
        assertTaskExecuted(":sub6:test")
        assertTaskExecuted(":sub7:test")
        assertTaskExecuted(":sub8:test")
        assertTaskExecuted(":sub9:test")
    }

    ProgressListener failingProgressListener() {
        new ProgressListener() {
            @Override
            void statusChanged(ProgressEvent event) {
                throw new GradleException("failing progress listener")
            }
        }
    }

    def assertBuildCancelled() {
        stdout.toString().contains("Build cancelled.")
        true
    }

    private void waitingForBuild() {
        ConcurrentTestUtil.poll {
            assert stdout.toString().contains("Waiting for changes to input files of tasks...");
        }
        stdout.reset()
        stderr.reset()
    }

    boolean assertTaskExecuted(String taskPath) {
        assert events.all.findAll { it instanceof TaskFinishEvent }.any { it.descriptor.taskPath == taskPath }
        true
    }

    def assertTaskNotExecuted(String taskPath) {
        assert !events.all.findAll { it instanceof TaskFinishEvent }.any { it.descriptor.taskPath == taskPath }
        true
    }

    def assertTaskNotUpToDate(String taskPath) {
        assert events.all.findAll { it instanceof TaskFinishEvent }.any { it.descriptor.taskPath == taskPath && !it.result.upToDate }
        true
    }

    def assertTestNotExecuted(Map testInfo) {
        assert !hasTestDescriptor(testInfo)
        true
    }

    def assertTestExecuted(Map testInfo) {
        assert hasTestDescriptor(testInfo)
        true
    }

    Collection<TestOperationDescriptor> testDescriptors(List<TestOperationDescriptor> descriptors = events.tests.collect { it.descriptor }, String className, String methodName, String taskpath) {

        def descriptorByClassAndMethod = descriptors.findAll { it.className == className && it.methodName == methodName }
        if (taskpath == null) {
            return descriptorByClassAndMethod
        }

        return descriptorByClassAndMethod.findAll {
            def parent = it.parent
            while (parent.parent != null) {
                parent = parent.parent
                if (parent instanceof TaskOperationDescriptor) {
                    return parent.taskPath == taskpath
                }
            }
            false
        }
    }

    Collection<TestOperationDescriptor> testDescriptors(List<TestOperationDescriptor> descriptors = events.tests.collect { it.descriptor }, String className, String methodName) {
        testDescriptors(descriptors, className, methodName, null)
    }

    Collection<TestOperationDescriptor> testDescriptors(List<TestOperationDescriptor> descriptors = events.tests.collect { it.descriptor }, String className) {
        testDescriptors(descriptors, className, null)
    }

    private boolean hasTestDescriptor(testInfo) {
        def collect = events.tests.collect { it.descriptor }
        !testDescriptors(collect, testInfo.className, testInfo.methodName, testInfo.task).isEmpty()
    }

    void launchTests(Collection<TestOperationDescriptor> testsToLaunch) {
        launchTests { TestLauncher testLauncher ->
            testLauncher.withTests(testsToLaunch)
        }
    }

    void launchTests(Closure configurationClosure) {
        withConnection { ProjectConnection connection ->
            launchTests(connection, null, cancellationTokenSource.token(), configurationClosure)
        }
    }

    void launchTests(ProjectConnection connection, ResultHandler<Void> resultHandler, CancellationToken cancellationToken, Closure configurationClosure) {
        TestLauncher testLauncher = connection.newTestLauncher()
            .withCancellationToken(cancellationToken)
            .addProgressListener(events)

        if (toolingApi.isEmbedded()) {
            testLauncher
                .setStandardOutput(stdout)
                .setStandardError(stderr)
        } else {
            testLauncher
                .setStandardOutput(new TeeOutputStream(stdout, System.out))
                .setStandardError(new TeeOutputStream(stderr, System.err))
        }

        configurationClosure.call(testLauncher)

        events.clear()
        if (resultHandler == null) {
            testLauncher.run()
        } else {
            testLauncher.run(resultHandler)
        }
    }

    private collectDescriptorsFromBuild() {
        try {
            withConnection {
                ProjectConnection connection ->
                    connection.newBuild().forTasks('build').withArguments("--continue").addProgressListener(events).run()
            }
        } catch (BuildException e) {
        }
    }

    def testCode() {
        settingsFile << "rootProject.name = 'testproject'\n"
        buildFile.text = simpleJavaProject()

        buildFile << """
            sourceSets {
                moreTests {
                    java.srcDir "src/test"
                    compileClasspath = compileClasspath + sourceSets.test.compileClasspath
                    runtimeClasspath = runtimeClasspath + sourceSets.test.runtimeClasspath
                }
            }

            task secondTest(type:Test) {
                classpath = sourceSets.moreTests.runtimeClasspath
                testClassesDir = sourceSets.moreTests.output.classesDir
            }

            build.dependsOn secondTest
        """

        file("src/test/java/example/MyTest.java") << """
            package example;
            public class MyTest {
                @org.junit.Test public void foo() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
                @org.junit.Test public void foo2() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
            }
        """

        file("src/test/java/example2/MyOtherTest.java") << """
            package example2;
            public class MyOtherTest {
                @org.junit.Test public void bar() throws Exception {
                     org.junit.Assert.assertEquals(2, 2);
                }
            }
        """
    }

    def changeTestSource() {
        // adding two more test methods
        file("src/test/java/example/MyTest.java").text = """
            package example;
            public class MyTest {
                @org.junit.Test public void foo() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
                @org.junit.Test public void foo2() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
                @org.junit.Test public void foo3() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
                @org.junit.Test public void foo4() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
            }
        """
    }

    def simpleJavaProject() {
        """
        allprojects{
            apply plugin: 'java'
            repositories { mavenCentral() }
            dependencies { testCompile 'junit:junit:4.12' }
        }
        """
    }

    def testClassRemoved() {
        file("src/test/java/example/MyTest.java").delete()
    }

}
