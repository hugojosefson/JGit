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
package org.spearce.jgit.lib;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.spearce.jgit.errors.MissingObjectException;

public class PackedObjectReader extends ObjectReader
{
    private final PackReader pack;

    private final long dataOffset;

    private final ObjectId deltaBase;

    private String objectType;

    private long objectSize;

    PackedObjectReader(
        final PackReader pr,
        final String type,
        final long size,
        final long offset,
        final ObjectId base)
    {
        pack = pr;
        dataOffset = offset;
        deltaBase = base;
        if (base != null)
        {
            objectSize = -1;
        }
        else
        {
            objectSize = size;
            objectType = type;
        }
    }

    public void setId(final ObjectId id)
    {
        super.setId(id);
    }

    public String getType() throws IOException
    {
        if (objectType == null && deltaBase != null)
        {
            objectType = baseReader().getType();
        }
        return objectType;
    }

    public long getSize() throws IOException
    {
        if (objectSize == -1 && deltaBase != null)
        {
            final PatchDeltaStream p;
            p = new PatchDeltaStream(packStream(), null);
            objectSize = p.getResultLength();
            p.close();
        }
        return objectSize;
    }

    public ObjectId getDeltaBaseId()
    {
        return deltaBase;
    }

    public long getDataOffset()
    {
        return dataOffset;
    }

    public InputStream getInputStream() throws IOException
    {
        if (deltaBase != null)
        {
            final ObjectReader b = baseReader();
            final PatchDeltaStream p = new PatchDeltaStream(packStream(), b);
            if (objectSize == -1)
            {
                objectSize = p.getResultLength();
            }
            if (objectType == null)
            {
                objectType = b.getType();
            }
            return p;
        }
        return packStream();
    }

    public void close() throws IOException
    {
    }

    private ObjectReader baseReader() throws IOException
    {
        final ObjectReader or = pack.resolveBase(getDeltaBaseId());
        if (or == null)
        {
            throw new MissingObjectException(deltaBase, "delta base");
        }
        return or;
    }

    private BufferedInputStream packStream()
    {
        return new BufferedInputStream(new PackStream());
    }

    private class PackStream extends InputStream
    {
        private Inflater inf = new Inflater(false);

        private byte[] in = new byte[2048];

        private long offset = getDataOffset();

        public int read() throws IOException
        {
            final byte[] sbb = new byte[1];
            return read(sbb, 0, 1) == 1 ? sbb[0] & 0xff : -1;
        }

        public int read(final byte[] b, final int off, final int len)
            throws IOException
        {
            if (inf.finished())
            {
                return -1;
            }

            if (inf.needsInput())
            {
                final int n = pack.read(offset, in, 0, in.length);
                inf.setInput(in, 0, n);
                offset += n;
            }

            try
            {
                final int n = inf.inflate(b, off, len);
                if (inf.finished())
                {
                    pack.unread(offset, inf.getRemaining());
                }
                return n;
            }
            catch (DataFormatException dfe)
            {
                final IOException e = new IOException("Corrupt ZIP stream.");
                e.initCause(dfe);
                throw e;
            }
        }

        public void close()
        {
            if (inf != null)
            {
                inf.end();
                inf = null;
                in = null;
            }
        }
    }
}