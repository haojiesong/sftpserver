/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.samsung.codez.sftpserver;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.apache.log4j.PropertyConfigurator;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.compression.Compression;
import org.apache.sshd.common.config.SshConfigFileReader;
import org.apache.sshd.common.config.keys.BuiltinIdentities;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.io.IoServiceFactory;
import org.apache.sshd.common.io.mina.MinaServiceFactory;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.ServerAuthenticationManager;
import org.apache.sshd.server.ServerFactoryManager;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.AbstractGeneratorHostKeyProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystem;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.samsung.codez.sftpserver.readonly.ReadOnlyRootedFileSystemProvider;

/**
 * SFTP Server
 * @author Song Haojie
 */
public class Server implements PasswordAuthenticator /*, PublickeyAuthenticator*/ {
    public static final String CONFIG_FILE = "/sftpd.properties";
    public static final String HTPASSWD_FILE = "/htpasswd";
    public static final String HOSTKEY_FILE_PEM = "keys/hostkey.pem";
    public static final String HOSTKEY_FILE_SER = "keys/hostkey.ser";

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private Config db;
    private SshServer sshd;
    private volatile boolean running = true;
    private static String SFTP_HOME;

    public static void main(final String[] args) {
        SFTP_HOME = System.getProperty("sftp.home");
        if (SFTP_HOME == null || "".equals(SFTP_HOME)) {
            System.err.println("Not specify sftp.home");
            return;
        }

        PropertyConfigurator.configure(SFTP_HOME + "/conf/log4j.properties");
        new Server().start();
    }

    protected void setupFactories() {
//        sshd.setSubsystemFactories(Arrays.<NamedFactory<Command>>asList(new CustomSftpSubsystemFactory()));
        sshd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sshd.setMacFactories(Arrays.<NamedFactory<Mac>>asList( //
                BuiltinMacs.hmacsha512, //
                BuiltinMacs.hmacsha256, //
                BuiltinMacs.hmacsha1));
        sshd.setChannelFactories(Arrays.<NamedFactory<Channel>>asList(ChannelSessionFactory.INSTANCE));
    }

    protected void setupDummyShell() {
//        sshd.setShellFactory(new SecureShellFactory());
        sshd.setShellFactory(InteractiveProcessShellFactory.INSTANCE);
    }

    /*
    protected void setupKeyPair() {
        if (SecurityUtils.isBouncyCastleRegistered()) {
            sshd.setKeyPairProvider(
                    SecurityUtils.createGeneratorHostKeyProvider(new File(HOSTKEY_FILE_PEM).toPath()));
        } else {
            sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File(HOSTKEY_FILE_SER)));
        }
    }
    */

    protected void setupKeyPair() {
        String hostKeyType = AbstractGeneratorHostKeyProvider.DEFAULT_ALGORITHM;
        int hostKeySize = 0;
        Collection<String> keyFiles = null;

        KeyPairProvider thisHostKeyProvider = null;

        if (GenericUtils.isEmpty(keyFiles)) {
            AbstractGeneratorHostKeyProvider hostKeyProvider;
            Path hostKeyFile;
            if (SecurityUtils.isBouncyCastleRegistered()) {
                hostKeyFile = new File("key.pem").toPath();
                hostKeyProvider = SecurityUtils.createGeneratorHostKeyProvider(hostKeyFile);
            } else {
                hostKeyFile = new File("key.ser").toPath();
                hostKeyProvider = new SimpleGeneratorHostKeyProvider(hostKeyFile);
            }
            hostKeyProvider.setAlgorithm(hostKeyType);
            if (hostKeySize != 0) {
                hostKeyProvider.setKeySize(hostKeySize);
            }

            List<KeyPair> keys = ValidateUtils.checkNotNullAndNotEmpty(hostKeyProvider.loadKeys(),
                    "Failed to load keys from %s", hostKeyFile);
            KeyPair kp = keys.get(0);
            PublicKey pubKey = kp.getPublic();
            String keyAlgorithm = pubKey.getAlgorithm();
            if (BuiltinIdentities.Constants.ECDSA.equalsIgnoreCase(keyAlgorithm)) {
                keyAlgorithm = KeyUtils.EC_ALGORITHM;
            } else if (BuiltinIdentities.Constants.ED25519.equals(keyAlgorithm)) {
                keyAlgorithm = SecurityUtils.EDDSA;
                // TODO change the hostKeyProvider to one that supports read/write of EDDSA keys - see SSHD-703
            }

            // force re-generation of host key if not same algorithm
            if (!Objects.equals(keyAlgorithm, hostKeyType)) {
                try {
                    Files.deleteIfExists(hostKeyFile);
                } catch (IOException e) {
                }
                hostKeyProvider.clearLoadedKeys();
            }

            thisHostKeyProvider = hostKeyProvider;
        } else {
            @SuppressWarnings("null")
            List<KeyPair> pairs = new ArrayList<>(keyFiles.size());
            for (String keyFilePath : keyFiles) {
                Path path = Paths.get(keyFilePath);
                try (InputStream inputStream = Files.newInputStream(path)) {
                    KeyPair kp = SecurityUtils.loadKeyPairIdentity(keyFilePath, inputStream, null);
                    pairs.add(kp);
                } catch (Exception e) {
                    LOG.error("Failed (" + e.getClass().getSimpleName() + ")"
                                + " to load host key file=" + keyFilePath
                                + ": " + e.getMessage());
                }
            }

            thisHostKeyProvider = new MappedKeyPairProvider(pairs);
        }

        sshd.setKeyPairProvider(thisHostKeyProvider);
    }

    protected void setupScp() {
        sshd.setCommandFactory(new ScpCommandFactory());
        sshd.setFileSystemFactory(new SecureFileSystemFactory(db));
        sshd.setTcpipForwardingFilter(null);
        sshd.setAgentFactory(null);
    }

    protected void setupAuth() {
        sshd.setPasswordAuthenticator(this);
//        sshd.setPublickeyAuthenticator(this);
        sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshd.setGSSAuthenticator(null);
    }

    protected void loadHtPasswd() throws IOException {
        InputStream is = null;
        BufferedReader r = null;
        try {
            final boolean htEnabled = Boolean.parseBoolean(db.getHtValue(Config.PROP_HT_ENABLED));
            if (!htEnabled) {
                return;
            }
            final String htHome = db.getHtValue(Config.PROP_HT_HOME);
            final boolean htEnableWrite = Boolean.parseBoolean(db.getHtValue(Config.PROP_HT_ENABLE_WRITE));
            // is = getClass().getResourceAsStream(HTPASSWD_FILE);
            File f = new File(SFTP_HOME + "/conf" + HTPASSWD_FILE);
            if (f.exists()) {
                is = new FileInputStream(f);
            }
            r = new BufferedReader(new InputStreamReader(is));
            if (is == null) {
                LOG.error("htpasswd file " + HTPASSWD_FILE + " not found in classpath");
                return;
            }
            String line = null;
            int c = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                final String[] tok = line.split(":", 2);
                if (tok.length != 2) {
                    continue;
                }
                final String user = tok[0];
                final String auth = tok[1];
                db.setValue(user, Config.PROP_PWD, auth);
                db.setValue(user, Config.PROP_HOME, htHome);
                db.setValue(user, Config.PROP_ENABLED, htEnabled);
                db.setValue(user, Config.PROP_ENABLE_WRITE, htEnableWrite);
                c++;
            }
            LOG.info("htpasswd file loaded " + c + " lines");
        } finally {
            closeQuietly(r);
            closeQuietly(is);
        }
    }

    protected void setupCompress() {
        // Compression is not enabled by default
        // You need download and compile:
        // http://www.jcraft.com/jzlib/
        sshd.setCompressionFactories(Arrays.<NamedFactory<Compression>>asList( //
                BuiltinCompressions.none, //
                BuiltinCompressions.zlib, //
                BuiltinCompressions.delayedZlib));
    }

    protected void setupServerBanner() {
        Map<String, String> options = new LinkedHashMap<>();
        String bannerOption = GenericUtils.isEmpty(options)
                ? null
                : Objects.toString(options.remove(SshConfigFileReader.BANNER_CONFIG_PROP), null);
        if (GenericUtils.isEmpty(bannerOption)) {
            bannerOption = GenericUtils.isEmpty(options)
                    ? null
                    : Objects.toString(options.remove(SshConfigFileReader.VISUAL_HOST_KEY), null);
            if (SshConfigFileReader.parseBooleanValue(bannerOption)) {
                bannerOption = ServerAuthenticationManager.AUTO_WELCOME_BANNER_VALUE;
            }
        }

        Object banner;
        if (GenericUtils.isNotEmpty(bannerOption)) {
            if ("none".equals(bannerOption)) {
                return;
            }

            if (ServerAuthenticationManager.AUTO_WELCOME_BANNER_VALUE.equalsIgnoreCase(bannerOption)) {
                banner = bannerOption;
            } else {
                banner = Paths.get(bannerOption);
            }
        } else {
            banner = "Welcome to HMS-SFTPD\n";
        }

        PropertyResolverUtils.updateProperty(sshd, ServerAuthenticationManager.WELCOME_BANNER, banner);
    }

    protected Config loadConfig() {
        final Properties db = new Properties();
        InputStream is = null;
        try {
            /*
            is = getClass().getResourceAsStream(CONFIG_FILE);
            if (is == null) {
                LOG.error("Config file " + CONFIG_FILE + " not found in classpath");
            } else {
                db.load(is);
                LOG.info("Config file loaded " + db.size() + " lines");
            }
            */
            File f = new File(SFTP_HOME + "/conf" + CONFIG_FILE);
            if (!f.exists()) {
                LOG.error("Config file " + CONFIG_FILE + " not found");
            } else {
                is = new FileInputStream(f);
                db.load(is);
                LOG.info("Config file loaded " + db.size() + " lines");
            }
        } catch (IOException e) {
            LOG.error("IOException " + e.toString(), e);
        } finally {
            closeQuietly(is);
        }
        return new Config(db);

    }

    private void closeQuietly(final Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ign) {
            }
        }
    }

    private void hackVersion() {
        PropertyResolverUtils.updateProperty(sshd, ServerFactoryManager.SERVER_IDENTIFICATION, "SSHD");
    }

    private void setupMaxPacketLength() {
        final int maxPacketLength = db.getMaxPacketLength();
        if (maxPacketLength > 1) {
            PropertyResolverUtils.updateProperty(sshd, SftpSubsystem.MAX_READDIR_DATA_SIZE_PROP, maxPacketLength);
        }
    }

    public void start() {
        LOG.info("Starting");
        db = loadConfig();

        // Setup provider
        String provider = db.getProvider();
        if (provider != null && !"".equals(provider)) {
            if ("mina".equals(provider)) {
                System.setProperty(IoServiceFactory.class.getName(), MinaServiceFactory.class.getName());
            } else if ("nio2".endsWith(provider)) {
                System.setProperty(IoServiceFactory.class.getName(), Nio2ServiceFactory.class.getName());
            } else {
                LOG.error("provider should be mina or nio2");
                return;
            }
        }

        sshd = SshServer.setUpDefaultServer();
        LOG.info("SSHD " + sshd.getVersion());
        hackVersion();
        setupMaxPacketLength();
        setupFactories();
        setupKeyPair();
        setupServerBanner();
        setupScp();
        setupAuth();

        sshd.setTcpipForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.setCommandFactory(new ScpCommandFactory.Builder().withDelegate(
            command -> new ProcessShellFactory(GenericUtils.split(command, ' ')).create()
        ).build());

        try {
            final int port = db.getPort();
            final boolean enableCompress = db.enableCompress();
            final boolean enableDummyShell = db.enableDummyShell();
            if (enableCompress) {
                setupCompress();
            }
            if (enableDummyShell) {
                setupDummyShell();
            }
            loadHtPasswd();
            sshd.setPort(port);
            LOG.info("Listen on port=" + port);
            final Server thisServer = this;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    thisServer.stop();
                }
            });
            sshd.start();
        } catch (Exception e) {
            LOG.error("Exception " + e.toString(), e);
        }
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("Stopped");
    }

    public void stop() {
        LOG.info("Stopping");
        running = false;
        try {
            sshd.stop();
        } catch (IOException e) {
            try {
                sshd.stop(true);
            } catch (IOException ee) {
                LOG.error("Failed to stop", ee);
            }
        }
    }

    @Override
    public boolean authenticate(final String username, final String password, final ServerSession session) {
        LOG.info("Request auth (Password) for username=" + username);
        if ((username != null) && (password != null)) {
            return db.checkUserPassword(username, password);
        }
        return false;
    }

    /*
    @Override
    public boolean authenticate(final String username, final PublicKey key, final ServerSession session) {
        LOG.info("Request auth (PublicKey) for username=" + username);
        // File f = new File("/home/" + username + "/.ssh/authorized_keys");
        if ((username != null) && (key != null)) {
            return db.checkUserPublicKey(username, key);
        }
        return false;
    }
    */

    // =================== Helper Classes

    static class Config {
        // Global config
        public static final String BASE = "sftpserver";
        public static final String PROP_GLOBAL = BASE + "." + "global";
        public static final String PROP_PORT = "port";
        public static final String PROP_COMPRESS = "compress";
        public static final String PROP_DUMMY_SHELL = "dummyshell";
        public static final String PROP_MAX_PACKET_LENGTH = "maxPacketLength";
        public static final String PROP_PROVIDER = "provider";
        // HtPasswd config
        public static final String PROP_HTPASSWD = BASE + "." + "htpasswd";
        public static final String PROP_HT_HOME = "homedirectory";
        public static final String PROP_HT_ENABLED = "enableflag";
        public static final String PROP_HT_ENABLE_WRITE = "writepermission"; // true / false
        // User config
        public static final String PROP_BASE_USERS = BASE + "." + "user";
        public static final String PROP_PWD = "userpassword";
        public static final String PROP_KEY = "userkey" + ".";
        public static final String PROP_HOME = "homedirectory";
        public static final String PROP_ENABLED = "enableflag"; // true / false
        public static final String PROP_ENABLE_WRITE = "writepermission"; // true / false

        private final Properties db;

        public Config(final Properties db) {
            this.db = db;
        }

        // Global config
        public boolean enableCompress() {
            return Boolean.parseBoolean(getValue(PROP_COMPRESS));
        }

        public boolean enableDummyShell() {
            return Boolean.parseBoolean(getValue(PROP_DUMMY_SHELL));
        }

        public int getPort() {
            return Integer.parseInt(getValue(PROP_PORT));
        }

        public String getProvider() {
            return getValue(PROP_PROVIDER);
        }

        public int getMaxPacketLength() {
            final String v = getValue(PROP_MAX_PACKET_LENGTH);
            if (v == null) {
                // FIXME: Workaround for BUG in SSHD-CORE
                // https://issues.apache.org/jira/browse/SSHD-725
                // https://issues.apache.org/jira/browse/SSHD-728
                return (64 * 1024); // 64KB
            }
            return Integer.parseInt(v);
        }

        private final String getValue(final String key) {
            if (key == null) {
                return null;
            }
            return db.getProperty(PROP_GLOBAL + "." + key);
        }

        private final String getHtValue(final String key) {
            if (key == null) {
                return null;
            }
            return db.getProperty(PROP_HTPASSWD + "." + key);
        }

        // User config
        private final String getValue(final String user, final String key) {
            if ((user == null) || (key == null))
                return null;
            final String value = db.getProperty(PROP_BASE_USERS + "." + user + "." + key);
            return ((value == null) ? null : value.trim());
        }

        private final void setValue(final String user, final String key, final Object value) {
            if ((user == null) || (key == null) || (value == null))
                return;
            db.setProperty(PROP_BASE_USERS + "." + user + "." + key, String.valueOf(value));
        }

        public boolean isEnabledUser(final String user) {
            final String value = getValue(user, PROP_ENABLED);
            if (value == null)
                return false;
            return Boolean.parseBoolean(value);
        }

        public boolean checkUserPassword(final String user, final String pwd) {
            final StringBuilder sb = new StringBuilder(96);
            boolean traceInfo = false;
            boolean authOk = false;
            sb.append("Request auth (Password) for username=").append(user).append(" ");
            try {
                if (!isEnabledUser(user)) {
                    sb.append("(user disabled)");
                    return authOk;
                }
                final String value = getValue(user, PROP_PWD);
                if (value == null) {
                    sb.append("(no password)");
                    return authOk;
                }
                final boolean isCrypted = PasswordEncrypt.isCrypted(value);
                authOk = isCrypted ? PasswordEncrypt.checkPassword(value, pwd) : value.equals(pwd);
                sb.append(isCrypted ? "(encrypted)" : "(unencrypted)");
                traceInfo = isCrypted;
            } finally {
                sb.append(": ").append(authOk ? "OK" : "FAIL");
                if (authOk) {
                    if (traceInfo) {
                        LOG.info(sb.toString());
                    } else {
                        LOG.warn(sb.toString());
                    }
                } else {
                    LOG.error(sb.toString());
                }
            }
            return authOk;
        }

        /*
        public boolean checkUserPublicKey(final String user, final PublicKey key) {
            final String encodedKey = PublicKeyHelper.getEncodedPublicKey(key);
            final StringBuilder sb = new StringBuilder(96);
            boolean authOk = false;
            sb.append("Request auth (PublicKey) for username=").append(user);
            sb.append(" (").append(key.getAlgorithm()).append(")");
            try {
                if (!isEnabledUser(user)) {
                    sb.append(" (user disabled)");
                    return authOk;
                }
                for (int i = 1; i < 1024; i++) {
                    final String value = getValue(user, PROP_KEY + i);
                    if (value == null) {
                        if (i == 1)
                            sb.append(" (no publickey)");
                        break;
                    } else if (value.equals(encodedKey)) {
                        authOk = true;
                        break;
                    }
                }
            } finally {
                sb.append(": ").append(authOk ? "OK" : "FAIL");
                if (authOk) {
                    LOG.info(sb.toString());
                } else {
                    LOG.error(sb.toString());
                }
            }
            return authOk;
        }
        */

        public String getHome(final String user) {
            try {
                String dir = getValue(user, PROP_HOME);
                if (dir == null || "".equals(dir)) {
                    dir = getValue(PROP_HOME);
                }
                final File home = new File(dir);
                if (home.isDirectory() && home.canRead()) {
                    return home.getCanonicalPath();
                }
            } catch (IOException e) {
            }
            return null;
        }

        public boolean hasWritePerm(final String user) {
            final String value = getValue(user, PROP_ENABLE_WRITE);
            return Boolean.parseBoolean(value);
        }
    }

    /*
    static class SecureShellFactory implements Factory<Command> {
        @Override
        public Command create() {
            return new SecureShellCommand();
        }
    }

    static class SecureShellCommand implements Command {
        private OutputStream err = null;
        private ExitCallback callback = null;

        @Override
        public void setInputStream(final InputStream in) {
        }

        @Override
        public void setOutputStream(final OutputStream out) {
        }

        @Override
        public void setErrorStream(final OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(final ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(final Environment env) throws IOException {
            if (err != null) {
                err.write("shell not allowed\r\n".getBytes("ISO-8859-1"));
                err.flush();
            }
            if (callback != null)
                callback.onExit(-1, "shell not allowed");
        }

        @Override
        public void destroy() {
        }
    }
    */

    /*
    static class CustomSftpSubsystemFactory extends SftpSubsystemFactory {
        @Override
        public Command create() {
            final SftpSubsystem subsystem = new SftpSubsystem(getExecutorService(), isShutdownOnExit(),
                    getUnsupportedAttributePolicy()) {
                @Override
                protected void setFileAttribute(final Path file, final String view, final String attribute,
                        final Object value, final LinkOption... options) throws IOException {
                    throw new UnsupportedOperationException("setFileAttribute Disabled");
                }

                @Override
                protected void createLink(final int id, final String targetPath, final String linkPath,
                        final boolean symLink) throws IOException {
                    throw new UnsupportedOperationException("createLink Disabled");
                }
            };
            final Collection<? extends SftpEventListener> listeners = getRegisteredListeners();
            if (GenericUtils.size(listeners) > 0) {
                for (final SftpEventListener l : listeners) {
                    subsystem.addSftpEventListener(l);
                }
            }
            return subsystem;
        }
    }
    */

    static class SecureFileSystemFactory implements FileSystemFactory {
        private final Config db;

        public SecureFileSystemFactory(final Config db) {
            this.db = db;
        }

        @Override
        public FileSystem createFileSystem(final Session session) throws IOException {
            final String userName = session.getUsername();
            final String home = db.getHome(userName);
            if (home == null) {
                throw new IOException("user home error");
            }
            final RootedFileSystemProvider rfsp = db.hasWritePerm(userName) ? new RootedFileSystemProvider()
                    : new ReadOnlyRootedFileSystemProvider();
            return rfsp.newFileSystem(Paths.get(home), Collections.<String, Object>emptyMap());
        }
    }

    // =================== PublicKeyHelper
    /*
    static class PublicKeyHelper {
        private static final Charset US_ASCII = Charset.forName("US-ASCII");

        public static String getEncodedPublicKey(final PublicKey pub) {
            if (pub instanceof RSAPublicKey) {
                return encodeRSAPublicKey((RSAPublicKey) pub);
            }
            if (pub instanceof DSAPublicKey) {
                return encodeDSAPublicKey((DSAPublicKey) pub);
            }
            return null;
        }

        public static String encodeRSAPublicKey(final RSAPublicKey key) {
            final BigInteger[] params = new BigInteger[] {
                    key.getPublicExponent(), key.getModulus()
            };
            return encodePublicKey(params, "ssh-rsa");
        }

        public static String encodeDSAPublicKey(final DSAPublicKey key) {
            final BigInteger[] params = new BigInteger[] {
                    key.getParams().getP(), key.getParams().getQ(), key.getParams().getG(), key.getY()
            };
            return encodePublicKey(params, "ssh-dss");
        }

        private static final void encodeUInt32(final IoBuffer bab, final int value) {
            bab.put((byte) ((value >> 24) & 0xFF));
            bab.put((byte) ((value >> 16) & 0xFF));
            bab.put((byte) ((value >> 8) & 0xFF));
            bab.put((byte) (value & 0xFF));
        }

        private static String encodePublicKey(final BigInteger[] params, final String keyType) {
            final IoBuffer bab = IoBuffer.allocate(256);
            bab.setAutoExpand(true);
            byte[] buf = null;
            // encode the header "ssh-dss" / "ssh-rsa"
            buf = keyType.getBytes(US_ASCII); // RFC-4253, pag.13
            encodeUInt32(bab, buf.length);    // RFC-4251, pag.8 (string encoding)
            for (final byte b : buf) {
                bab.put(b);
            }
            // encode params
            for (final BigInteger param : params) {
                buf = param.toByteArray();
                encodeUInt32(bab, buf.length);
                for (final byte b : buf) {
                    bab.put(b);
                }
            }
            bab.flip();
            buf = new byte[bab.limit()];
            System.arraycopy(bab.array(), 0, buf, 0, buf.length);
            bab.free();
            return keyType + " " + DatatypeConverter.printBase64Binary(buf);
        }
    }
    */
}
