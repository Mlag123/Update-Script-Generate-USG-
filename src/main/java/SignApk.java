import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import sun.misc.BASE64Encoder;
import sun.security.pkcs.ContentInfo;
import sun.security.pkcs.PKCS7;
import sun.security.pkcs.SignerInfo;
import sun.security.x509.AlgorithmId;
import sun.security.x509.X500Name;


@SuppressWarnings("restriction")
public class SignApk {
    private static final String CERT_SF_NAME = "META-INF/CERT.SF";
    private static final String CERT_RSA_NAME = "META-INF/CERT.RSA";

    private static final String OTACERT_NAME = "META-INF/com/android/otacert";

    // Files matching this pattern are not copied to the output.
    private static Pattern stripPattern =
            Pattern.compile("^META-INF/(.*)[.](SF|RSA|DSA)$");

    private static X509Certificate readPublicKey(File file)
            throws IOException, GeneralSecurityException {
        FileInputStream input = new FileInputStream(file);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(input);
        } finally {
            input.close();
        }
    }

    /**
     * Reads the password from stdin and returns it as a string.
     *
     * @param keyFile The file containing the private key.  Used to prompt the user.
     */
    private static String readPassword(File keyFile) {
        // TODO: use Console.readPassword() when it's available.
        System.out.print("Enter password for " + keyFile + " (password will not be hidden): ");
        System.out.flush();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            return stdin.readLine();
        } catch (IOException ex) {
            return null;
        }
    }

    private static KeySpec decryptPrivateKey(byte[] encryptedPrivateKey, File keyFile)
            throws GeneralSecurityException {
        EncryptedPrivateKeyInfo epkInfo;
        try {
            epkInfo = new EncryptedPrivateKeyInfo(encryptedPrivateKey);
        } catch (IOException ex) {
            // Probably not an encrypted key.
            return null;
        }

        char[] password = readPassword(keyFile).toCharArray();

        SecretKeyFactory skFactory = SecretKeyFactory.getInstance(epkInfo.getAlgName());
        Key key = skFactory.generateSecret(new PBEKeySpec(password));

        Cipher cipher = Cipher.getInstance(epkInfo.getAlgName());
        cipher.init(Cipher.DECRYPT_MODE, key, epkInfo.getAlgParameters());

        try {
            return epkInfo.getKeySpec(cipher);
        } catch (InvalidKeySpecException ex) {
            System.err.println("signapk: Password for " + keyFile + " may be bad.");
            throw ex;
        }
    }

    /**
     * Read a PKCS 8 format private key.
     */
    private static PrivateKey readPrivateKey(File file)
            throws IOException, GeneralSecurityException {
        DataInputStream input = new DataInputStream(new FileInputStream(file));
        try {
            byte[] bytes = new byte[(int) file.length()];
            input.read(bytes);

            KeySpec spec = decryptPrivateKey(bytes, file);
            if (spec == null) {
                spec = new PKCS8EncodedKeySpec(bytes);
            }

            try {
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (InvalidKeySpecException ex) {
                return KeyFactory.getInstance("DSA").generatePrivate(spec);
            }
        } finally {
            input.close();
        }
    }

    /**
     * Add the SHA1 of every file to the manifest, creating it if necessary.
     */
    private static Manifest addDigestsToManifest(JarFile jar)
            throws IOException, GeneralSecurityException {
        Manifest input = jar.getManifest();
        Manifest output = new Manifest();
        Attributes main = output.getMainAttributes();
        if (input != null) {
            main.putAll(input.getMainAttributes());
        } else {
            main.putValue("Manifest-Version", "1.0");
        }

        BASE64Encoder base64 = new BASE64Encoder();
        MessageDigest md = MessageDigest.getInstance("SHA1");
        byte[] buffer = new byte[4096];
        int num;

        // We sort the input entries by name, and add them to the
        // output manifest in sorted order.  We expect that the output
        // map will be deterministic.

        TreeMap<String, JarEntry> byName = new TreeMap<String, JarEntry>();

        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            byName.put(entry.getName(), entry);
        }

        for (JarEntry entry : byName.values()) {
            String name = entry.getName();
            if (!entry.isDirectory() && !name.equals(JarFile.MANIFEST_NAME) &&
                    !name.equals(CERT_SF_NAME) && !name.equals(CERT_RSA_NAME) &&
                    !name.equals(OTACERT_NAME) &&
                    (stripPattern == null ||
                            !stripPattern.matcher(name).matches())) {
                InputStream data = jar.getInputStream(entry);
                while ((num = data.read(buffer)) > 0) {
                    md.update(buffer, 0, num);
                }

                Attributes attr = null;
                if (input != null) attr = input.getAttributes(name);
                attr = attr != null ? new Attributes(attr) : new Attributes();
                attr.putValue("SHA1-Digest", base64.encode(md.digest()));
                output.getEntries().put(name, attr);
            }
        }

        return output;
    }

    private static void addOtacert(JarOutputStream outputJar,
                                   File publicKeyFile,
                                   long timestamp,
                                   Manifest manifest)
            throws IOException, GeneralSecurityException {
        BASE64Encoder base64 = new BASE64Encoder();
        MessageDigest md = MessageDigest.getInstance("SHA1");

        JarEntry je = new JarEntry(OTACERT_NAME);
        je.setTime(timestamp);
        outputJar.putNextEntry(je);
        FileInputStream input = new FileInputStream(publicKeyFile);
        byte[] b = new byte[4096];
        int read;
        while ((read = input.read(b)) != -1) {
            outputJar.write(b, 0, read);
            md.update(b, 0, read);
        }
        input.close();

        Attributes attr = new Attributes();
        attr.putValue("SHA1-Digest", base64.encode(md.digest()));
        manifest.getEntries().put(OTACERT_NAME, attr);
    }


    private static class SignatureOutputStream extends FilterOutputStream {
        private Signature mSignature;
        private int mCount;

        public SignatureOutputStream(OutputStream out, Signature sig) {
            super(out);
            mSignature = sig;
            mCount = 0;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                mSignature.update((byte) b);
            } catch (SignatureException e) {
                throw new IOException("SignatureException: " + e);
            }
            super.write(b);
            mCount++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                mSignature.update(b, off, len);
            } catch (SignatureException e) {
                throw new IOException("SignatureException: " + e);
            }
            super.write(b, off, len);
            mCount += len;
        }

        public int size() {
            return mCount;
        }
    }

     private static void writeSignatureFile(Manifest manifest, SignatureOutputStream out)
            throws IOException, GeneralSecurityException {
        Manifest sf = new Manifest();
        Attributes main = sf.getMainAttributes();
        main.putValue("Signature-Version", "1.0");

        BASE64Encoder base64 = new BASE64Encoder();
        MessageDigest md = MessageDigest.getInstance("SHA1");
        PrintStream print = new PrintStream(
                new DigestOutputStream(new ByteArrayOutputStream(), md),
                true, "UTF-8");

        // Digest of the entire manifest
        manifest.write(print);
        print.flush();
        main.putValue("SHA1-Digest-Manifest", base64.encode(md.digest()));

        Map<String, Attributes> entries = manifest.getEntries();
        for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
            // Digest of the manifest stanza for this entry.
            print.print("Name: " + entry.getKey() + "\r\n");
            for (Map.Entry<Object, Object> att : entry.getValue().entrySet()) {
                print.print(att.getKey() + ": " + att.getValue() + "\r\n");
            }
            print.print("\r\n");
            print.flush();

            Attributes sfAttr = new Attributes();
            sfAttr.putValue("SHA1-Digest", base64.encode(md.digest()));
            sf.getEntries().put(entry.getKey(), sfAttr);
        }

        sf.write(out);

        if ((out.size() % 1024) == 0) {
            out.write('\r');
            out.write('\n');
        }
    }

    private static void writeSignatureBlock(
            Signature signature, X509Certificate publicKey, OutputStream out)
            throws IOException, GeneralSecurityException {
        SignerInfo signerInfo = new SignerInfo(
                new X500Name(publicKey.getIssuerX500Principal().getName()),
                publicKey.getSerialNumber(),
                AlgorithmId.get("SHA1"),
                AlgorithmId.get("RSA"),
                signature.sign());

        PKCS7 pkcs7 = new PKCS7(
                new AlgorithmId[]{AlgorithmId.get("SHA1")},
                new ContentInfo(ContentInfo.DATA_OID, null),
                new X509Certificate[]{publicKey},
                new SignerInfo[]{signerInfo});

        pkcs7.encodeSignedData(out);
    }

    private static void signWholeOutputFile(byte[] zipData,
                                            OutputStream outputStream,
                                            X509Certificate publicKey,
                                            PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        if (zipData[zipData.length - 22] != 0x50 ||
                zipData[zipData.length - 21] != 0x4b ||
                zipData[zipData.length - 20] != 0x05 ||
                zipData[zipData.length - 19] != 0x06) {
            throw new IllegalArgumentException("zip data already has an archive comment");
        }

        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(privateKey);
        signature.update(zipData, 0, zipData.length - 2);

        ByteArrayOutputStream temp = new ByteArrayOutputStream();

        byte[] message = "signed by SignApk".getBytes("UTF-8");
        temp.write(message);
        temp.write(0);
        writeSignatureBlock(signature, publicKey, temp);
        int total_size = temp.size() + 6;
        if (total_size > 0xffff) {
            throw new IllegalArgumentException("signature is too big for ZIP file comment");
        }
        // signature starts this many bytes from the end of the file
        int signature_start = total_size - message.length - 1;
        temp.write(signature_start & 0xff);
        temp.write((signature_start >> 8) & 0xff);

        temp.write(0xff);
        temp.write(0xff);
        temp.write(total_size & 0xff);
        temp.write((total_size >> 8) & 0xff);
        temp.flush();

        byte[] b = temp.toByteArray();
        for (int i = 0; i < b.length - 3; ++i) {
            if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06) {
                throw new IllegalArgumentException("found spurious EOCD header at " + i);
            }
        }

        outputStream.write(zipData, 0, zipData.length - 2);
        outputStream.write(total_size & 0xff);
        outputStream.write((total_size >> 8) & 0xff);
        temp.writeTo(outputStream);
    }

    private static void copyFiles(Manifest manifest,
                                  JarFile in, JarOutputStream out, long timestamp) throws IOException {
        byte[] buffer = new byte[4096];
        int num;

        Map<String, Attributes> entries = manifest.getEntries();
        List<String> names = new ArrayList<String>(entries.keySet());
        Collections.sort(names);
        for (String name : names) {
            JarEntry inEntry = in.getJarEntry(name);
            JarEntry outEntry = null;
            if (inEntry.getMethod() == JarEntry.STORED) {
                // Preserve the STORED method of the input entry.
                outEntry = new JarEntry(inEntry);
            } else {
                // Create a new entry so that the compressed len is recomputed.
                outEntry = new JarEntry(name);
            }
            outEntry.setTime(timestamp);
            out.putNextEntry(outEntry);

            InputStream data = in.getInputStream(inEntry);
            while ((num = data.read(buffer)) > 0) {
                out.write(buffer, 0, num);
            }
            out.flush();
        }
    }

    public static void main(String[] args) {
        if (args.length != 4 && args.length != 5) {
            System.err.println("Usage: signapk [-w] " +
                    "publickey.x509[.pem] privatekey.pk8 " +
                    "input.jar output.jar");
            System.exit(2);
        }

        boolean signWholeFile = false;
        int argstart = 0;
        if (args[0].equals("-w")) {
            signWholeFile = true;
            argstart = 1;
        }

        JarFile inputJar = null;
        JarOutputStream outputJar = null;
        FileOutputStream outputFile = null;

        try {
            File publicKeyFile = new File(args[argstart + 0]);
            X509Certificate publicKey = readPublicKey(publicKeyFile);

            // Assume the certificate is valid for at least an hour.
            long timestamp = publicKey.getNotBefore().getTime() + 3600L * 1000;

            PrivateKey privateKey = readPrivateKey(new File(args[argstart + 1]));
            inputJar = new JarFile(new File(args[argstart + 2]), false);  // Don't verify.

            OutputStream outputStream = null;
            if (signWholeFile) {
                outputStream = new ByteArrayOutputStream();
            } else {
                outputStream = outputFile = new FileOutputStream(args[argstart + 3]);
            }
            outputJar = new JarOutputStream(outputStream);
            outputJar.setLevel(9);

            JarEntry je;

            Manifest manifest = addDigestsToManifest(inputJar);

            // Everything else
            copyFiles(manifest, inputJar, outputJar, timestamp);

            // otacert
            if (signWholeFile) {
                addOtacert(outputJar, publicKeyFile, timestamp, manifest);
            }

            // MANIFEST.MF
            je = new JarEntry(JarFile.MANIFEST_NAME);
            je.setTime(timestamp);
            outputJar.putNextEntry(je);
            manifest.write(outputJar);

            // CERT.SF
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(privateKey);
            je = new JarEntry(CERT_SF_NAME);
            je.setTime(timestamp);
            outputJar.putNextEntry(je);
            writeSignatureFile(manifest,
                    new SignatureOutputStream(outputJar, signature));

            // CERT.RSA
            je = new JarEntry(CERT_RSA_NAME);
            je.setTime(timestamp);
            outputJar.putNextEntry(je);
            writeSignatureBlock(signature, publicKey, outputJar);

            outputJar.close();
            outputJar = null;
            outputStream.flush();

            if (signWholeFile) {
                outputFile = new FileOutputStream(args[argstart + 3]);
                signWholeOutputFile(((ByteArrayOutputStream) outputStream).toByteArray(),
                        outputFile, publicKey, privateKey);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            try {
                if (inputJar != null) inputJar.close();
                if (outputFile != null) outputFile.close();
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}
