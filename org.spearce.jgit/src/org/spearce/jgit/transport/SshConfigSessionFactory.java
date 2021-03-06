/*
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2009, JetBrains s.r.o.
 * Copyright (C) 2009, Google, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Git Development Community nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.spearce.jgit.transport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.spearce.jgit.util.FS;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

/**
 * The base session factory that loads known hosts and private keys from
 * <code>$HOME/.ssh</code>.
 * <p>
 * This is the default implementation used by JGit and provides most of the
 * compatibility necessary to match OpenSSH, a popular implementation of SSH
 * used by C Git.
 * <p>
 * The factory does not provide UI behavior. Override the method
 * {@link #configure(org.spearce.jgit.transport.OpenSshConfig.Host, Session)}
 * to supply appropriate {@link UserInfo} to the session.
 */
public abstract class SshConfigSessionFactory extends SshSessionFactory {
	private final Map<String, JSch> byIdentityFile = new HashMap<String, JSch>();

	private JSch defaultJSch;

	private OpenSshConfig config;

	@Override
	public synchronized Session getSession(String user, String pass,
			String host, int port) throws JSchException {
		final OpenSshConfig.Host hc = getConfig().lookup(host);
		host = hc.getHostName();
		if (port <= 0)
			port = hc.getPort();
		if (user == null)
			user = hc.getUser();

		final Session session = createSession(hc, user, host, port);
		if (pass != null)
			session.setPassword(pass);
		final String strictHostKeyCheckingPolicy = hc
				.getStrictHostKeyChecking();
		if (strictHostKeyCheckingPolicy != null)
			session.setConfig("StrictHostKeyChecking",
					strictHostKeyCheckingPolicy);
		final String pauth = hc.getPreferredAuthentications();
		if (pauth != null)
			session.setConfig("PreferredAuthentications", pauth);
		configure(hc, session);
		return session;
	}

	/**
	 * Create a new JSch session for the requested address.
	 *
	 * @param hc
	 *            host configuration
	 * @param user
	 *            login to authenticate as.
	 * @param host
	 *            server name to connect to.
	 * @param port
	 *            port number of the SSH daemon (typically 22).
	 * @return new session instance, but otherwise unconfigured.
	 * @throws JSchException
	 *             the session could not be created.
	 */
	protected Session createSession(final OpenSshConfig.Host hc,
			final String user, final String host, final int port)
			throws JSchException {
		return getJSch(hc).getSession(user, host, port);
	}

	/**
	 * Provide additional configuration for the session based on the host
	 * information. This method could be used to supply {@link UserInfo}.
	 *
	 * @param hc
	 *            host configuration
	 * @param session
	 *            session to configure
	 */
	protected abstract void configure(OpenSshConfig.Host hc, Session session);

	/**
	 * Obtain the JSch used to create new sessions.
	 *
	 * @param hc
	 *            host configuration
	 * @return the JSch instance to use.
	 * @throws JSchException
	 *             the user configuration could not be created.
	 */
	protected JSch getJSch(final OpenSshConfig.Host hc) throws JSchException {
		final JSch def = getDefaultJSch();
		final File identityFile = hc.getIdentityFile();
		if (identityFile == null) {
			return def;
		}

		final String identityKey = identityFile.getAbsolutePath();
		JSch jsch = byIdentityFile.get(identityKey);
		if (jsch == null) {
			jsch = new JSch();
			jsch.setHostKeyRepository(def.getHostKeyRepository());
			jsch.addIdentity(identityKey);
			byIdentityFile.put(identityKey, jsch);
		}
		return jsch;
	}

	private JSch getDefaultJSch() throws JSchException {
		if (defaultJSch == null) {
			defaultJSch = createDefaultJSch();
			for (Object name : defaultJSch.getIdentityNames()) {
				byIdentityFile.put((String) name, defaultJSch);
			}
		}
		return defaultJSch;
	}

	/**
	 * @return the new default JSch implementation.
	 * @throws JSchException
	 *             known host keys cannot be loaded.
	 */
	protected JSch createDefaultJSch() throws JSchException {
		final JSch jsch = new JSch();
		knownHosts(jsch);
		identities(jsch);
		return jsch;
	}

	private OpenSshConfig getConfig() {
		if (config == null)
			config = OpenSshConfig.get();
		return config;
	}

	private static void knownHosts(final JSch sch) throws JSchException {
		final File home = FS.userHome();
		if (home == null)
			return;
		final File known_hosts = new File(new File(home, ".ssh"), "known_hosts");
		try {
			final FileInputStream in = new FileInputStream(known_hosts);
			try {
				sch.setKnownHosts(in);
			} finally {
				in.close();
			}
		} catch (FileNotFoundException none) {
			// Oh well. They don't have a known hosts in home.
		} catch (IOException err) {
			// Oh well. They don't have a known hosts in home.
		}
	}

	private static void identities(final JSch sch) {
		final File home = FS.userHome();
		if (home == null)
			return;
		final File sshdir = new File(home, ".ssh");
		if (sshdir.isDirectory()) {
			loadIdentity(sch, new File(sshdir, "identity"));
			loadIdentity(sch, new File(sshdir, "id_rsa"));
			loadIdentity(sch, new File(sshdir, "id_dsa"));
		}
	}

	private static void loadIdentity(final JSch sch, final File priv) {
		if (priv.isFile()) {
			try {
				sch.addIdentity(priv.getAbsolutePath());
			} catch (JSchException e) {
				// Instead, pretend the key doesn't exist.
			}
		}
	}

	@Override
	public OutputStream getErrorStream() {
		return new OutputStream() {
			private StringBuilder all = new StringBuilder();

			private StringBuilder sb = new StringBuilder();

			public String toString() {
				String r = all.toString();
				while (r.endsWith("\n"))
					r = r.substring(0, r.length() - 1);
				return r;
			}

			@Override
			public void write(final int b) throws IOException {
				if (b == '\r') {
					System.err.print('\r');
					return;
				}

				sb.append((char) b);

				if (b == '\n') {
					final String line = sb.toString();
					System.err.print(line);
					all.append(line);
					sb = new StringBuilder();
				}
			}
		};
	}
}
