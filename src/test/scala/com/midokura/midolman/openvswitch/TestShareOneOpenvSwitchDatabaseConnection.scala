/**
 * TestShareOneOpenvSwitchDatabaseConnection.scala - Share a single
 *              OVSDB connection between all the tests using OVSDB.
 *
 * Copyright 2011 Midokura Inc.
 *
 * This is needed because currently OpenvSwitchDatabaseConnectionImpl
 * breaks if you try to use it multiply.  The sharing is accomplished by
 * having an junit Suite make the connection, then call all the classes
 * registered with it.  To make a new class for testing a client of OVSDB,
 * have it import TestShareOneOpenvSwitchDatabaseConnection._ and 
 * acquire/release mutex in its @BeforeClass/@AfterClass, and add it
 * to the list of SuiteClasses.
 */
 
package com.midokura.midolman.openvswitch

import org.junit.{AfterClass, BeforeClass, Test}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.Suite

import com.midokura.midolman.CheckBridgeControllerOVS
import com.midokura.midolman.openvswitch._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl._
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConsts._

import java.io.{File, RandomAccessFile}
import java.lang.Long.parseLong
import java.nio.channels.FileLock
import java.util.Date
import java.util.concurrent.Semaphore

object TestShareOneOpenvSwitchDatabaseConnection {
    private final val database = "Open_vSwitch"
    private final val host = "localhost"
    private final val port = 12344
    final val bridgeName = "testovsbr"
    final val bridgeExtIdKey = "midolman-vnet"
    final val bridgeExtIdValue = "efbf1194-9e25-11e0-b3b3-ba417460eb69"
    final var bridgeId: Long = _
    final var ovsdb: OpenvSwitchDatabaseConnectionImpl = _
    final val target = "tcp:127.0.0.1:6634"
    private final val lockfile = new File("/tmp/ovs_tests.lock")
    private final val lockchannel =
        new RandomAccessFile(lockfile, "rw").getChannel
    private var lock: FileLock = _
    final var mutex = new Semaphore(1)

    @BeforeClass def connectToOVSDB() {
        lock = lockchannel.lock
        Console.err.println("Entering testOVSConn at " + new Date)
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(database, host, port)
        testAddBridge
        bridgeId = parseLong(ovsdb.getDatapathId(bridgeName), 16)
        ovsdb.delTargetOpenflowControllers(target)
        assertFalse(ovsdb.hasController(target))
    }

    @AfterClass def disconnectFromOVSDB() {
        testDelBridge
        assertFalse(ovsdb.hasBridge(bridgeName))
        ovsdb.close
        Console.err.println("Closing testOVSConn at " + new Date)
        lock.release
    }

    /**
     * Test addBridge().
     */
    def testAddBridge() = {
        val bb: BridgeBuilder = ovsdb.addBridge(bridgeName)
        bb.externalId(bridgeExtIdKey, bridgeExtIdValue)
        bb.build
        assertTrue(ovsdb.hasBridge(bridgeName))
    }

    /**
     * Test delBridge().
     */
    def testDelBridge() = {
        ovsdb.delBridge(bridgeName)
        assertFalse(ovsdb.hasBridge(bridgeName))
    }
}

@RunWith(classOf[Suite])
@Suite.SuiteClasses(Array(classOf[CheckOpenvSwitchDatabaseConnection],
                          classOf[CheckBridgeControllerOVS]))
class TestShareOneOpenvSwitchDatabaseConnection {
    import TestShareOneOpenvSwitchDatabaseConnection._
}
