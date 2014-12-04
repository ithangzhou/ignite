/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.IgniteEventType.*;

/**
 * Event storage tests.
 *
 * Note:
 * Test based on events generated by test task execution.
 * Filter class must be static because it will be send to remote host in
 * serialized form.
 */
@GridCommonTest(group = "Kernal Self")
public class GridEventStorageSelfTest extends GridCommonAbstractTest {
    /** First grid. */
    private static Ignite ignite1;

    /** Second grid. */
    private static Ignite ignite2;

    /** */
    public GridEventStorageSelfTest() {
        super(/*start grid*/false);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ignite1 = startGrid(1);
        ignite2 = startGrid(2);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception In case of error.
     */
    public void testAddRemoveGlobalListener() throws Exception {
        IgnitePredicate<IgniteEvent> lsnr = new IgnitePredicate<IgniteEvent>() {
            @Override public boolean apply(IgniteEvent evt) {
                info("Received local event: " + evt);

                return true;
            }
        };

        ignite1.events().localListen(lsnr, EVTS_ALL_MINUS_METRIC_UPDATE);

        assert ignite1.events().stopLocalListen(lsnr);
    }

    /**
     * @throws Exception In case of error.
     */
    public void testAddRemoveDiscoListener() throws Exception {
        IgnitePredicate<IgniteEvent> lsnr = new IgnitePredicate<IgniteEvent>() {
            @Override public boolean apply(IgniteEvent evt) {
                info("Received local event: " + evt);

                return true;
            }
        };

        ignite1.events().localListen(lsnr, EVT_NODE_LEFT, EVT_NODE_FAILED);

        assert ignite1.events().stopLocalListen(lsnr);
        assert !ignite1.events().stopLocalListen(lsnr);
    }

    /**
     * @throws Exception In case of error.
     */
    public void testLocalNodeEventStorage() throws Exception {
        TestEventListener lsnr = new TestEventListener();

        IgnitePredicate<IgniteEvent> filter = new TestEventFilter();

        // Check that two same listeners may be added.
        ignite1.events().localListen(lsnr, EVT_TASK_STARTED);
        ignite1.events().localListen(lsnr, EVT_TASK_STARTED);

        // Execute task.
        generateEvents(ignite1);

        assert lsnr.getCounter() == 1;

        Collection<IgniteEvent> evts = ignite1.events().localQuery(filter);

        assert evts != null;
        assert evts.size() == 1;

        // Execute task.
        generateEvents(ignite1);

        // Check that listener has been removed.
        assert lsnr.getCounter() == 2;

        // Check that no problems with nonexistent listeners.
        assert ignite1.events().stopLocalListen(lsnr);
        assert !ignite1.events().stopLocalListen(lsnr);

        // Check for events from local node.
        evts = ignite1.events().localQuery(filter);

        assert evts != null;
        assert evts.size() == 2;

        // Check for events from empty remote nodes collection.
        try {
            events(ignite1.cluster().forPredicate(F.<ClusterNode>alwaysFalse())).remoteQuery(filter, 0);
        }
        catch (GridEmptyProjectionException ignored) {
            // No-op
        }
    }

    /**
     * @throws Exception In case of error.
     */
    public void testRemoteNodeEventStorage() throws Exception {
        IgnitePredicate<IgniteEvent> filter = new TestEventFilter();

        generateEvents(ignite2);

        ClusterGroup prj = ignite1.cluster().forPredicate(F.remoteNodes(ignite1.cluster().localNode().id()));

        Collection<IgniteEvent> evts = events(prj).remoteQuery(filter, 0);

        assert evts != null;
        assert evts.size() == 1;
    }

    /**
     * @throws Exception In case of error.
     */
    public void testRemoteAndLocalNodeEventStorage() throws Exception {
        IgnitePredicate<IgniteEvent> filter = new TestEventFilter();

        generateEvents(ignite1);

        Collection<IgniteEvent> evts = ignite1.events().remoteQuery(filter, 0);
        Collection<IgniteEvent> locEvts = ignite1.events().localQuery(filter);
        Collection<IgniteEvent> remEvts =
            events(ignite1.cluster().forPredicate(F.remoteNodes(ignite1.cluster().localNode().id()))).remoteQuery(filter, 0);

        assert evts != null;
        assert locEvts != null;
        assert remEvts != null;
        assert evts.size() == 1;
        assert locEvts.size() == 1;
        assert remEvts.isEmpty();
    }

    /**
     * Create events in grid.
     *
     * @param ignite Grid.
     * @throws GridException In case of error.
     */
    private void generateEvents(Ignite ignite) throws GridException {
        ignite.compute().localDeployTask(GridEventTestTask.class, GridEventTestTask.class.getClassLoader());

        ignite.compute().execute(GridEventTestTask.class.getName(), null);
    }

    /**
     * Test task.
     */
    private static class GridEventTestTask extends ComputeTaskSplitAdapter<Object, Object> {
        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, Object arg) throws GridException {
            return Collections.singleton(new GridEventTestJob());
        }

        /** {@inheritDoc} */
        @Override public Serializable reduce(List<ComputeJobResult> results) throws GridException {
            assert results != null;
            assert results.size() == 1;

            return results.get(0).getData();
        }
    }

    /**
     * Test job.
     */
    private static class GridEventTestJob extends ComputeJobAdapter {
        /** {@inheritDoc} */
        @Override public String execute() throws GridException {
            return "GridEventTestJob-test-event.";
        }
    }

    /**
     * Test event listener.
     */
    private class TestEventListener implements IgnitePredicate<IgniteEvent> {
        /** Event counter. */
        private AtomicInteger cnt = new AtomicInteger();

        /** {@inheritDoc} */
        @Override public boolean apply(IgniteEvent evt) {
            info("Event storage event: evt=" + evt);

            // Count only started tasks.
            if (evt.type() == EVT_TASK_STARTED)
                cnt.incrementAndGet();

            return true;
        }

        /**
         * @return Event counter value.
         */
        public int getCounter() {
            return cnt.get();
        }

        /**
         * Clear event counter.
         */
        public void clearCounter() {
            cnt.set(0);
        }
    }

    /**
     * Test event filter.
     */
    private static class TestEventFilter implements IgnitePredicate<IgniteEvent> {
        /** {@inheritDoc} */
        @Override public boolean apply(IgniteEvent evt) {
            // Accept only predefined TASK_STARTED events.
            return evt.type() == EVT_TASK_STARTED;
        }
    }
}
