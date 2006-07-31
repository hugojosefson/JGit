/*
 *    Copyright 2006 Shawn Pearce <spearce@spearce.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spearce.egit.core.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.spearce.egit.core.Activator;
import org.spearce.egit.core.CoreText;
import org.spearce.jgit.lib.FileTreeEntry;
import org.spearce.jgit.lib.ObjectWriter;
import org.spearce.jgit.lib.Tree;
import org.spearce.jgit.lib.TreeEntry;

public class CheckpointJob extends Job
{
    private static final Object EXISTS = new Object();

    private static final int MB = 1024 * 1024;

    private final Set blobQueue;

    private final Collection blobRules;

    private final Map treeQueue;

    private final ObjectWriter ow;

    private final RepositoryMapping rm;

    private long byteCnt;

    private int objectCnt;

    public CheckpointJob(final RepositoryMapping m)
    {
        super(CoreText.bind(CoreText.CheckpointJob_name, m.getContainer()
            .getFullPath()));
        setPriority(Job.LONG);

        blobQueue = new HashSet();
        blobRules = new ArrayList();
        treeQueue = new LinkedHashMap();
        ow = new ObjectWriter(m.getRepository());
        rm = m;
    }

    public void scheduleIfNecessary()
    {
        if (!blobQueue.isEmpty() || !treeQueue.isEmpty())
        {
            final ISchedulingRule[] r = new ISchedulingRule[blobRules.size()];
            blobRules.toArray(r);
            setRule(MultiRule.combine(r));
            trace("scheduling");
            schedule();
        }
    }

    public void enqueue(
        final ISchedulingRule rule,
        final File s,
        final FileTreeEntry e)
    {
        if (rule != null && e.isModified() && s.canRead())
        {
            if (blobQueue.add(new QueuedBlob(s, e)))
            {
                blobRules.add(rule);
                byteCnt += s.length();
                objectCnt++;
            }
        }
    }

    public void enqueue(final Tree t)
    {
        if (t.isModified())
        {
            if (treeQueue.put(t, EXISTS) == null)
            {
                objectCnt++;
            }
        }
    }

    protected IStatus run(IProgressMonitor monitor)
    {
        if (monitor == null)
        {
            monitor = new NullProgressMonitor();
        }

        trace("running");
        monitor.beginTask(CoreText.CheckpointJob_writing, (int) (byteCnt / MB)
            + objectCnt);
        try
        {
            Iterator i;
            boolean wroteSomething;

            monitor.subTask(CoreText.CheckpointJob_writingBlobs);
            i = blobQueue.iterator();
            while (i.hasNext())
            {
                if (monitor.isCanceled())
                {
                    trace("canceled");
                    return Status.CANCEL_STATUS;
                }

                final QueuedBlob q = (QueuedBlob) i.next();
                synchronized (q.ent)
                {
                    if (q.ent.isModified() && q.src.canRead())
                    {
                        q.ent.setId(ow.writeBlob(q.src));
                    }
                }
                monitor.worked((int) (q.src.length() / MB) + 1);
            }

            monitor.subTask(CoreText.CheckpointJob_writingTrees);
            wroteSomething = true;
            while (!treeQueue.isEmpty() && wroteSomething)
            {
                i = treeQueue.keySet().iterator();
                wroteSomething = false;
                PICK_TREE: while (i.hasNext())
                {
                    if (monitor.isCanceled())
                    {
                        trace("canceled");
                        return Status.CANCEL_STATUS;
                    }

                    final Tree t = (Tree) i.next();
                    synchronized (t)
                    {
                        if (t.isModified())
                        {
                            final TreeEntry[] m = t.members();
                            for (int p = 0; p < m.length; p++)
                            {
                                if (m[p].isModified())
                                {
                                    continue PICK_TREE;
                                }
                            }
                            t.setId(ow.writeTree(t));
                        }
                    }
                    wroteSomething = true;
                    monitor.worked(1);
                    i.remove();
                }
            }

            synchronized (rm)
            {
                if (!rm.getCacheTree().isModified())
                {
                    monitor.subTask(CoreText.CheckpointJob_writingRef);
                    trace("writing ref");
                    rm.saveCache();
                }
                else
                {
                    trace("tree is still dirty; ref can't be written");
                }
            }
        }
        catch (IOException ioe)
        {
            return Activator.error(CoreText.CheckpointJob_failed, ioe)
                .getStatus();
        }
        finally
        {
            trace("done");
            monitor.done();
        }

        return Status.OK_STATUS;
    }

    private void trace(final String m)
    {
        Activator.trace("(CheckpointJob "
            + rm.getContainer().getFullPath()
            + ") "
            + m);
    }

    private static class QueuedBlob
    {
        final File src;

        final FileTreeEntry ent;

        QueuedBlob(final File s, final FileTreeEntry e)
        {
            src = s;
            ent = e;
        }

        public int hashCode()
        {
            return ent.hashCode();
        }

        public boolean equals(final Object o)
        {
            return ent.equals(((QueuedBlob) o).ent);
        }
    }
}