/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package dunit;

import com.gemstone.gemfire.admin.internal.AdminDistributedSystemImpl;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import dunit.standalone.DUnitLauncher;
import junit.framework.TestCase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import util.TestException;

import java.io.File;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.security.PrivilegedAction;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class is the superclass of all distributed unit tests.
 *
 * tests/hydra/JUnitTestTask is the main DUnit driver. It supports two
 * additional public static methods if they are defined in the test case:
 *
 * public static void caseSetUp() -- comparable to JUnit's BeforeClass annotation
 *
 * public static void caseTearDown() -- comparable to JUnit's AfterClass annotation
 *
 * @author David Whitlock
 */
@SuppressWarnings("serial")
public abstract class DistributedTestBase extends TestCase implements java.io.Serializable {
  protected static final Logger globalLogger = LogManager.getLogger(Host.BASE_LOGGER_NAME);
  protected final Logger logger = LogManager.getLogger(getClass());
  private static final LinkedHashSet<String> testHistory = new LinkedHashSet<String>();

  /** This VM's connection to the distributed system */
  private static Properties lastSystemProperties;
  public static volatile String testName;

  private static ConcurrentLinkedQueue<ExpectedException> expectedExceptions =
      new ConcurrentLinkedQueue<ExpectedException>();

  /** For formatting timing info */
  private static final DecimalFormat format = new DecimalFormat("###.###");

  public static boolean reconnect = false;

  public static final boolean logPerTest = Boolean.getBoolean("dunitLogPerTest");
    /** the system line separator */

    public static final String lineSeparator = java.security.AccessController
        .doPrivileged(new PrivilegedAction<String>() {
          @Override
          public String run() {
            return System.getProperty("line.separator");
          }
        });

  public static InternalDistributedSystem system;
  private static Class lastSystemCreatedInTest;

    ///////////////////////  Utility Methods  ///////////////////////

  public final static Properties getAllDistributedSystemProperties(Properties props) {
    Properties p = DUnitEnv.get().getDistributedSystemProperties();

    // our tests do not expect auto-reconnect to be on by default
    if (!p.contains(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME)) {
      p.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "true");
    }

    for (Iterator iter = props.entrySet().iterator();
         iter.hasNext(); ) {
      Map.Entry entry = (Map.Entry) iter.next();
      String key = (String) entry.getKey();
      Object value = entry.getValue();
      p.put(key, value);
    }
    return p;
  }

  public /*final*/ InternalDistributedSystem getSystem() {
    return getSystem(new Properties());
  }

  public /*final*/ InternalDistributedSystem getSystem(Properties props) {
    // Setting the default disk store name is now done in setUp
    if (system == null) {
      system = InternalDistributedSystem.getAnyInstance();
    }
    if (system == null || !system.isConnected()) {
      // Figure out our distributed system properties
      Properties p = getAllDistributedSystemProperties(props);
      lastSystemCreatedInTest = getTestClass();
      if (logPerTest) {
        String testMethod = getTestName();
        String testName = lastSystemCreatedInTest.getName() + '-' + testMethod;
        String oldLogFile = p.getProperty(DistributionConfig.LOG_FILE_NAME);
        p.put(DistributionConfig.LOG_FILE_NAME,
            oldLogFile.replace("system.log", testName+".log"));
        String oldStatFile = p.getProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME);
        p.put(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
            oldStatFile.replace("statArchive.gfs", testName+".gfs"));
      }
      system = (InternalDistributedSystem) DistributedSystem.connect(p);
      lastSystemProperties = p;
    } else {
      boolean needNewSystem = false;
      if(!getTestClass().equals(lastSystemCreatedInTest)) {
        Properties newProps = getAllDistributedSystemProperties(props);
        needNewSystem = !newProps.equals(lastSystemProperties);
        if(needNewSystem) {
          logger.info(
              "Test class has changed and the new DS properties are not an exact match. "
                  + "Forcing DS disconnect. Old props = "
                  + lastSystemProperties + "new props=" + newProps);
        }
      } else {
        Properties activeProps = system.getProperties();
        for (Iterator iter = props.entrySet().iterator();
             iter.hasNext(); ) {
          Map.Entry entry = (Map.Entry) iter.next();
          String key = (String) entry.getKey();
          String value = (String) entry.getValue();
          if (!value.equals(activeProps.getProperty(key))) {
            needNewSystem = true;
            logger.info("Forcing DS disconnect. For property " + key
                + " old value = " + activeProps.getProperty(key)
                + " new value = " + value);
            break;
          }
        }
      }
      if(needNewSystem) {
        // the current system does not meet our needs to disconnect and
        // call recursively to get a new system.
        logger.info("Disconnecting from current DS in order to make a new one");
        disconnectFromDS();
        getSystem(props);
      }
    }
    return system;
  }

  public static void disconnectFromDS() {
    testName = null;
    GemFireCacheImpl.testCacheXml = null;
    if (system != null) {
      system.disconnect();
      system = null;
    }

    for (;;) {
      DistributedSystem ds = InternalDistributedSystem.getConnectedInstance();
      if (ds == null) {
        break;
      }
      try {
        ds.disconnect();
      }
      catch (Exception e) {
        // ignore
      }
    }

    {
      AdminDistributedSystemImpl ads =
          AdminDistributedSystemImpl.getConnectedInstance();
      if (ads != null) {// && ads.isConnected()) {
        ads.disconnect();
      }
    }
  }

  /**
   * Invokes a <code>SerializableRunnable</code> in every VM that
   * DUnit knows about.
   *
   * @see VM#invoke(Runnable)
   */
  public static void invokeInEveryVM(SerializableRunnable work) {
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);

      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        vm.invoke(work);
      }
    }
  }

  public static void invokeInLocator(SerializableRunnable work) {
    Host.getLocator().invoke(work);
  }

  /**
   * Invokes a <code>SerializableCallable</code> in every VM that
   * DUnit knows about.
   *
   * @return a Map of results, where the key is the VM and the value is the result
   */
  protected static Map invokeInEveryVM(SerializableCallable work) {
    HashMap ret = new HashMap();
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);
      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        ret.put(vm, vm.invoke(work));
      }
    }
    return ret;
  }

  /**
   * Invokes a method in every remote VM that DUnit knows about.
   *
   * @see VM#invoke(Class, String)
   */
  protected static void invokeInEveryVM(Class c, String method) {
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);

      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        vm.invoke(c, method);
      }
    }
  }

  /**
   * Invokes a method in every remote VM that DUnit knows about.
   *
   * @see VM#invoke(Class, String)
   */
  protected static void invokeInEveryVM(Class c, String method, Object[] methodArgs) {
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);

      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        vm.invoke(c, method, methodArgs);
      }
    }
  }

  public static void invokeInVM(VM vm, final String className, final String method,
                                final Object[] methodArgs) {
    SerializableRunnable run = new SerializableRunnable() {
      @Override
      public void run() {
        try {
          final Class<?> c = Class.forName(className);
          DistributedTestBase testBase = ((DistributedTestBase) c.getConstructor(
              String.class).newInstance("tmp"));
          for (Method m : c.getMethods()) {
            if (m.getName().equals(method)) {
              m.invoke(testBase, methodArgs);
              return;
            }
          }
          throw new TestException("No method with name " + method +
              " found in class " + className);
        } catch (Exception e) {
          String msg = "Failed in " + method + " on " /* TODO: get local VM or pid */;
          globalLogger.error(msg, e);
          throw new TestException(msg, e);
        }
      }
    };
  }

  /**
   * Invokes a method in every remote VM that DUnit knows about.
   *
   * @see VM#invoke(Class, String)
   */
  public void invokeInVM(VM vm, final String method,
                         final Object... methodArgs) {
    invokeInVM(vm, getClass().getName(), method, methodArgs);
  }

  /**
   * The number of milliseconds to try repeating validation code in the
   * event that AssertionFailedError is thrown.  For ACK scopes, no
   * repeat should be necessary.
   */
  protected long getRepeatTimeoutMs() {
    return 0;
  }

  protected void invokeRepeatingIfNecessary(VM vm, RepeatableRunnable task) {
    vm.invokeRepeatingIfNecessary(task, getRepeatTimeoutMs());
  }

  /**
   * Invokes a <code>SerializableRunnable</code> in every VM that
   * DUnit knows about.  If work.run() throws an assertion failure,
   * its execution is repeated, until no assertion failure occurs or
   * repeatTimeout milliseconds have passed.
   *
   * @see VM#invoke(Runnable)
   */
  protected void invokeInEveryVMRepeatingIfNecessary(RepeatableRunnable work) {
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);

      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        vm.invokeRepeatingIfNecessary(work, getRepeatTimeoutMs());
      }
    }
  }

  /** Return the total number of VMs on all hosts */
  protected static int getVMCount() {
    int count = 0;
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);
      count += host.getVMCount();
    }
    return count;
  }

  /** print a stack dump for this vm
   @author bruce
   @since 5.0
   */
  public static void dumpStack() {
    dumpStack(globalLogger);
  }

  /** print a stack dump for this vm
      @author bruce
      @since 5.0
   */
  public static void dumpStack(Logger logger) {
    StringBuilder sb = new StringBuilder();
    sb.append("STACK DUMP:").append(lineSeparator).append(lineSeparator);
    generateThreadDump(sb);
    logger.info(sb.toString());
  }

  /** print a stack dump for the given vm
      @author bruce
      @since 5.0
   */
  public static void dumpStack(VM vm) {
    vm.invoke(DistributedTestBase.class, "dumpStack");
  }

  /** print stack dumps for all vms on the given host
      @author bruce
      @since 5.0
   */
  public static void dumpStack(Host host) {
    for (int v=0; v < host.getVMCount(); v++) {
      host.getVM(v).invoke(DistributedTestBase.class, "dumpStack");
    }
  }

  /** print stack dumps for all vms
      @author bruce
      @since 5.0
   */
  public static void dumpAllStacks() {
    for (int h=0; h < Host.getHostCount(); h++) {
      dumpStack(Host.getHost(h));
    }
  }

  public static void generateThreadDump(StringBuilder msg) {
    ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
    for (ThreadInfo tInfo : mbean.dumpAllThreads(true, true)) {
      msg.append('"').append(tInfo.getThreadName()).append('"').append(" Id=")
          .append(tInfo.getThreadId()).append(' ')
          .append(tInfo.getThreadState());
      if (tInfo.getLockName() != null) {
        msg.append(" on ").append(tInfo.getLockName());
      }
      if (tInfo.getLockOwnerName() != null) {
        msg.append(" owned by \"").append(tInfo.getLockOwnerName())
            .append("\" Id=").append(tInfo.getLockOwnerId());
      }
      if (tInfo.isSuspended()) {
        msg.append(" (suspended)");
      }
      if (tInfo.isInNative()) {
        msg.append(" (in native)");
      }
      msg.append(lineSeparator);
      final StackTraceElement[] stackTrace = tInfo.getStackTrace();
      for (int index = 0; index < stackTrace.length; ++index) {
        msg.append("\tat ").append(stackTrace[index].toString())
            .append(lineSeparator);
        if (index == 0 && tInfo.getLockInfo() != null) {
          final Thread.State ts = tInfo.getThreadState();
          switch (ts) {
            case BLOCKED:
              msg.append("\t-  blocked on ").append(tInfo.getLockInfo())
                  .append(lineSeparator);
              break;
            case WAITING:
              msg.append("\t-  waiting on ").append(tInfo.getLockInfo())
                  .append(lineSeparator);
              break;
            case TIMED_WAITING:
              msg.append("\t-  waiting on ").append(tInfo.getLockInfo())
                  .append(lineSeparator);
              break;
            default:
          }
        }

        for (MonitorInfo mi : tInfo.getLockedMonitors()) {
          if (mi.getLockedStackDepth() == index) {
            msg.append("\t-  locked ").append(mi)
                .append(lineSeparator);
          }
        }
      }

      final LockInfo[] locks = tInfo.getLockedSynchronizers();
      if (locks.length > 0) {
        msg.append(lineSeparator)
            .append("\tNumber of locked synchronizers = ").append(locks.length)
            .append(lineSeparator);
        for (LockInfo li : locks) {
          msg.append("\t- ").append(li).append(lineSeparator);
        }
      }
      msg.append(lineSeparator);
    }
  }

  public static String noteTiming(long operations, String operationUnit,
                                  long beginTime, long endTime,
                                  String timeUnit)
  {
    long delta = endTime - beginTime;
    StringBuffer sb = new StringBuffer();
    sb.append("  Performed ");
    sb.append(operations);
    sb.append(" ");
    sb.append(operationUnit);
    sb.append(" in ");
    sb.append(delta);
    sb.append(" ");
    sb.append(timeUnit);
    sb.append("\n");

    double ratio = ((double) operations) / ((double) delta);
    sb.append("    ");
    sb.append(format.format(ratio));
    sb.append(" ");
    sb.append(operationUnit);
    sb.append(" per ");
    sb.append(timeUnit);
    sb.append("\n");

    ratio = ((double) delta) / ((double) operations);
    sb.append("    ");
    sb.append(format.format(ratio));
    sb.append(" ");
    sb.append(timeUnit);
    sb.append(" per ");
    sb.append(operationUnit);
    sb.append("\n");

    return sb.toString();
  }

  ////////////////////////  Constructors  ////////////////////////

  /**
   * Creates a new <code>DistributedTestBase</code> test with the
   * given name.
   */
  public DistributedTestBase(String name) {
    super(name);
    DUnitLauncher.launchIfNeeded();
  }

  ///////////////////////  Instance Methods  ///////////////////////

  protected Class getTestClass() {
    Class clazz = getClass();
    while (clazz.getDeclaringClass() != null) {
      clazz = clazz.getDeclaringClass();
    }
    return clazz;
  }

  /**
   * This finds the log level configured for the test run.  It should be used
   * when creating a new distributed system if you want to specify a log level.
   * @return the dunit log-level setting
   */
  public String getLogLevel() {
    return logger.getLevel().toString();
  }

  private String getDefaultDiskStoreName() {
    String vmid = System.getProperty("vmid");
    return "DiskStore-"  + vmid + "-"+ getTestClass().getCanonicalName() + "." + getTestName();
  }

  /**
   * Sets up the test (noop).
   */
  @Override
  public void setUp() throws Exception {
    logTestHistory();
    testName = getName();

    if (testName != null) {
      String baseDefaultDiskStoreName = getTestClass().getCanonicalName() + "." + getTestName();
      final String className = getClass().getName();
      for (int h = 0; h < Host.getHostCount(); h++) {
        Host host = Host.getHost(h);
        for (int v = 0; v < host.getVMCount(); v++) {
          VM vm = host.getVM(v);
          final String vmDefaultDiskStoreName = "DiskStore-" + h + "-" + v +
              "-" + baseDefaultDiskStoreName;
          invokeInVM(vm, "perVMSetup", testName, vmDefaultDiskStoreName);
        }
      }
    }
    System.out.println("\n\n[setup] START TEST " + getClass().getSimpleName() + "." + testName + "\n\n");
  }

  /**
   * Write a message to the log about what tests have ran previously. This
   * makes it easier to figure out if a previous test may have caused problems
   */
  protected void logTestHistory() {
    String classname = getClass().getSimpleName();
    testHistory.add(classname);
    System.out.println("Previously run tests: " + testHistory);
  }

  public void perVMSetUp(String name, String defaultDiskStoreName) {
    setTestName(name);
  }

  public static void setTestName(String name) {
    testName = name;
  }

  public static String getTestName() {
    return testName;
  }

  /**
   * For logPerTest to work, we have to disconnect from the DS, but all
   * subclasses do not call super.tearDown(). To prevent this scenario
   * this method has been declared final. Subclasses must now override
   * {@link #tearDown2()} instead.
   * @throws Exception
   */
  @Override
  public final void tearDown() throws Exception {
    tearDown2();
    final String className = getClass().getName();
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);
      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        invokeInVM(vm, "perVMTearDown", testName);
      }
    }
    tearDownAfter();
  }

  public void perVMTearDown(String name) {
    setTestName(null);
  }

  /**
   * Tears down the test. This method is called by the final {@link #tearDown()} method and should be overridden to
   * perform actual test cleanup and release resources used by the test.  The tasks executed by this method are
   * performed before the DUnit test framework using Hydra cleans up the client VMs.
   * <p/>
   * @throws Exception if the tear down process and test cleanup fails.
   * @see #tearDown
   * @see #tearDownAfter()
   */
  // TODO rename this method to tearDownBefore and change the access modifier to protected!
  public void tearDown2() throws Exception {
  }

  /**
   * Tears down the test.  Performs additional tear down tasks after the DUnit tests framework using Hydra cleans up
   * the client VMs.  This method is called by the final {@link #tearDown()} method and should be overridden to perform
   * post tear down activities.
   * <p/>
   * @throws Exception if the test tear down process fails.
   * @see #tearDown()
   * @see #tearDown2()
   */
  protected void tearDownAfter() throws Exception {
  }

  /**
   * Strip the package off and gives just the class name.
   * Needed because of Windows file name limits.
   */
  private String getShortClassName() {
    String result = this.getClass().getName();
    int idx = result.lastIndexOf('.');
    if (idx != -1) {
      result = result.substring(idx+1);
    }
    return result;
  }

  /** get the host name to use for a server cache in client/server dunit
   * testing
   * @param host
   * @return the host name
   */
  public static String getServerHostName(Host host) {
    return System.getProperty("gemfire.server-bind-address") != null?
        System.getProperty("gemfire.server-bind-address")
        : host.getHostName();
  }

  /** get the IP literal name for the current host, use this instead of
   * "localhost" to avoid IPv6 name resolution bugs in the JDK/machine config.
   * @return an ip literal, this method honors java.net.preferIPvAddresses
   */
  public static String getIPLiteral() {
    try {
      return SocketCreator.getLocalHost().getHostAddress();
    } catch (UnknownHostException e) {
      throw new Error("problem determining host IP address", e);
    }
  }

  /**
   * Get the port that the standard dunit locator is listening on.
   * @return
   */
  public static int getDUnitLocatorPort() {
    return DUnitEnv.get().getLocatorPort();
  }


  /**
   * Returns a unique name for this test method.  It is based on the
   * name of the class as well as the name of the method.
   */
  public String getUniqueName() {
    return getShortClassName() + "_" + this.getName();
  }

  /**
   * Helper method that causes this test to fail because of the given
   * exception.
   */
  public static void fail(String message, Throwable ex) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw, true);
    pw.print(message);
    pw.print(": ");
    ex.printStackTrace(pw);
    fail(sw.toString());
  }

  // utility methods

  /** pause for a default interval */
  protected void pause() {
    pause(250);
  }

  /**
   * Use of this function indicates a place in the tests tree where t
   * he use of Thread.sleep() is
   * highly questionable.
   * <p>
   * Some places in the system, especially those that test expirations and other
   * timeouts, have a very good reason to call {@link Thread#sleep(long)}.  The
   * <em>other</em> places are marked by the use of this method.
   *
   * @param ms
   */
  static public final void staticPause(int ms) {
//    getLogWriter().info("FIXME: Pausing for " + ms + " ms..."/*, new Exception()*/);
    final long target = System.currentTimeMillis() + ms;
    try {
      for (;;) {
        long msLeft = target - System.currentTimeMillis();
        if (msLeft <= 0) {
          break;
        }
        Thread.sleep(msLeft);
      }
    }
    catch (InterruptedException e) {
      fail("interrupted", e);
    }

  }

  /** pause for specified ms interval
   * Make sure system clock has advanced by the specified number of millis before
   * returning.
   */
  public static final void pause(int ms) {
    if (ms > 50) {
      globalLogger.info("Pausing for " + ms + " ms..."/*, new Exception()*/);
    }
    final long target = System.currentTimeMillis() + ms;
    try {
      for (;;) {
        long msLeft = target - System.currentTimeMillis();
        if (msLeft <= 0) {
          break;
        }
        Thread.sleep(msLeft);
      }
    }
    catch (InterruptedException e) {
      fail("interrupted", e);
    }
  }

  public interface WaitCriterion {
    public boolean done();
    public String description();
  }

  public interface WaitCriterion2 extends WaitCriterion {
    /**
     * If this method returns true then quit waiting even if we are not done.
     * This allows a wait to fail early.
     */
    public boolean stopWaiting();
  }

  /**
   * If true, we randomize the amount of time we wait before polling a
   * {@link WaitCriterion}.
   */
  static private final boolean USE_JITTER = true;
  static private final Random jitter = new Random();

  /**
   * Return a jittered interval up to a maximum of <code>ms</code>
   * milliseconds, inclusive.
   *
   * The result is bounded by 50 ms as a minimum and 5000 ms as a maximum.
   *
   * @param ms total amount of time to wait
   * @return randomized interval we should wait
   */
  private static int jitterInterval(long ms) {
    final int minLegal = 50;
    final int maxLegal = 5000;
    if (ms <= minLegal) {
      return (int)ms; // Don't ever jitter anything below this.
    }

    int maxReturn = maxLegal;
    if (ms < maxLegal) {
      maxReturn = (int)ms;
    }

    return minLegal + jitter.nextInt(maxReturn - minLegal + 1);
  }

  /**
   * Wait until given criterion is met
   * @param ev criterion to wait on
   * @param ms total time to wait, in milliseconds
   * @param interval pause interval between waits
   * @param throwOnTimeout if false, don't generate an error
   */
  static public void waitForCriterion(WaitCriterion ev, long ms,
      long interval, boolean throwOnTimeout) {
    long waitThisTime;
    if (USE_JITTER) {
      waitThisTime = jitterInterval(interval);
    }
    else {
      waitThisTime = interval;
    }
    final long tilt = System.currentTimeMillis() + ms;
    for (;;) {
//      getLogWriter().info("Testing to see if event has occurred: " + ev.description());
      if (ev.done()) {
        return; // success
      }
      if (ev instanceof WaitCriterion2) {
        WaitCriterion2 ev2 = (WaitCriterion2)ev;
        if (ev2.stopWaiting()) {
          if (throwOnTimeout) {
            fail("stopWaiting returned true: " + ev.description());
          }
          return;
        }
      }

      // Calculate time left
      long timeLeft = tilt - System.currentTimeMillis();
      if (timeLeft <= 0) {
        if (!throwOnTimeout) {
          return; // not an error, but we're done
        }
        fail("Event never occurred after " + ms + " ms: " + ev.description());
      }

      if (waitThisTime > timeLeft) {
        waitThisTime = timeLeft;
      }

      // Wait a little bit
      Thread.yield();
      try {
//        getLogWriter().info("waiting " + waitThisTime + "ms for " + ev.description());
        Thread.sleep(waitThisTime);
      } catch (InterruptedException e) {
        fail("interrupted");
      }
    }
  }

  /**
   * Wait on a mutex.  This is done in a loop in order to address the
   * "spurious wakeup" "feature" in Java.
   * @param ev condition to test
   * @param mutex object to lock and wait on
   * @param ms total amount of time to wait
   * @param interval interval to pause for the wait
   * @param throwOnTimeout if false, no error is thrown.
   */
  static public void waitMutex(WaitCriterion ev, Object mutex, long ms,
      long interval, boolean throwOnTimeout) {
    final long tilt = System.currentTimeMillis() + ms;
    long waitThisTime;
    if (USE_JITTER) {
      waitThisTime = jitterInterval(interval);
    }
    else {
      waitThisTime = interval;
    }
    synchronized (mutex) {
      for (;;) {
        if (ev.done()) {
          break;
        }

        long timeLeft = tilt - System.currentTimeMillis();
        if (timeLeft <= 0) {
          if (!throwOnTimeout) {
            return; // not an error, but we're done
          }
          fail("Event never occurred after " + ms + " ms: " + ev.description());
        }

        if (waitThisTime > timeLeft) {
          waitThisTime = timeLeft;
        }

        try {
          mutex.wait(waitThisTime);
        } catch (InterruptedException e) {
          fail("interrupted");
        }
      } // for
    } // synchronized
  }

  /**
   * Wait for a thread to join
   * @param t thread to wait on
   * @param ms maximum time to wait
   */
  static public void join(Thread t, long ms, Logger logger) {
    final long tilt = System.currentTimeMillis() + ms;
    final long incrementalWait;
    if (USE_JITTER) {
      incrementalWait = jitterInterval(ms);
    }
    else {
      incrementalWait = ms; // wait entire time, no looping.
    }
    final long start = System.currentTimeMillis();
    for (;;) {
      // I really do *not* understand why this check is necessary
      // but it is, at least with JDK 1.6.  According to the source code
      // and the javadocs, one would think that join() would exit immediately
      // if the thread is dead.  However, I can tell you from experimentation
      // that this is not the case. :-(  djp 2008-12-08
      if (!t.isAlive()) {
        break;
      }
      try {
        t.join(incrementalWait);
      } catch (InterruptedException e) {
        fail("interrupted");
      }
      if (System.currentTimeMillis() >= tilt) {
        break;
      }
    } // for
    if (logger == null) {
      logger = globalLogger;
    }
    if (t.isAlive()) {
      logger.info("HUNG THREAD");
      dumpStackTrace(t, t.getStackTrace(), logger);
      dumpStack(logger);
      t.interrupt(); // We're in trouble!
      fail("Thread did not terminate after " + ms + " ms: " + t);
//      getLogWriter().warning("Thread did not terminate" 
//          /* , new Exception()*/
//          );
    }
    long elapsedMs = (System.currentTimeMillis() - start);
    if (elapsedMs > 0) {
      String msg = "Thread " + t + " took "
        + elapsedMs
        + " ms to exit.";
      logger.info(msg);
    }
  }

  public static void dumpStackTrace(Thread t, StackTraceElement[] stack, Logger logger) {
    StringBuilder msg = new StringBuilder();
    msg.append("Thread=<")
      .append(t)
      .append("> stackDump:\n");
    for (int i=0; i < stack.length; i++) {
      msg.append("\t")
        .append(stack[i])
        .append("\n");
    }
    logger.info(msg.toString());
  }

  public static void staticLogString(String msg) {
    globalLogger.info(msg);
  }

  /**
   * A class that represents an currently logged expected exception, which
   * should be removed
   *
   * @author Mitch Thomas
   * @since 5.7bugfix
   */
  public static class ExpectedException implements Serializable {
    private static final long serialVersionUID = 1L;

    final String ex;

    final transient VM v;

    public ExpectedException(String exception) {
      this.ex = exception;
      this.v = null;
    }

    ExpectedException(String exception, VM vm) {
      this.ex = exception;
      this.v = vm;
    }

    public String getRemoveString() {
      return "<ExpectedException action=remove>" + ex + "</ExpectedException>";
    }

    public String getAddString() {
      return "<ExpectedException action=add>" + ex + "</ExpectedException>";
    }

    public void remove() {
      String s = getRemoveString();
      if (this.v != null) {
        this.v.invoke(DistributedTestBase.class, "staticLogString", new Object[] { s });
      } else {
        invokeInEveryVM(DistributedTestBase.class, "staticLogString", new Object[]{s});
      }
      globalLogger.info(s);
    }
  }

  /**
   * Log in all VMs, in both the test logger and the GemFire logger the
   * expected exception string to prevent grep logs from complaining. The
   * expected string is used by the GrepLogs utility and so can contain
   * regular expression characters.
   *
   * If you do not remove the expected exception, it will be removed at the
   * end of your test case automatically.
   *
   * @since 5.7bugfix
   * @param exception
   *          the exception string to expect
   * @return an ExpectedException instance for removal
   */
  public static ExpectedException addExpectedException(final String exception) {
    return addExpectedException(exception, null);
  }

  /**
   * Log in all VMs, in both the test logger and the GemFire logger the
   * expected exception string to prevent grep logs from complaining. The
   * expected string is used by the GrepLogs utility and so can contain
   * regular expression characters.
   *
   * @since 5.7bugfix
   * @param exception
   *          the exception string to expect
   * @param v
   *          the VM on which to log the expected exception or null for all VMs
   * @return an ExpectedException instance for removal purposes
   */
  public static ExpectedException addExpectedException(final String exception,
      VM v) {
    final ExpectedException ret;
    if (v != null) {
      ret = new ExpectedException(exception, v);
    }
    else {
      ret = new ExpectedException(exception);
    }
    // define the add and remove expected exceptions
    final String add = ret.getAddString();
    if (v != null) {
      v.invoke(DistributedTestBase.class, "staticLogString", new Object[] { add });
    }
    else {
      invokeInEveryVM(DistributedTestBase.class, "staticLogString", new Object[]{add});
    }
    globalLogger.info(add);
    expectedExceptions.add(ret);
    return ret;
  }

  /**
   * delete locator state files.  Use this after getting a random port
   * to ensure that an old locator state file isn't picked up by the
   * new locator you're starting.
   * @param ports
   */
  public void deleteLocatorStateFile(int... ports) {
    for (int i=0; i<ports.length; i++) {
      File stateFile = new File("locator"+ports[i]+"state.dat");
      if (stateFile.exists()) {
        stateFile.delete();
      }
    }
  }
}
