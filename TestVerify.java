import java.io.*;
import java.security.cert.*;
import java.util.*;
import sun.security.pkcs.PKCS7;
import sun.security.pkcs.SignerInfo;

public class TestVerify {
    public static void main(String[] args) throws Exception {
        File packageFile = new File("D:/apps/emas-ota/workspace_injection/output/update.zip");
        final long fileLen = packageFile.length();
        final RandomAccessFile raf = new RandomAccessFile(packageFile, "r");
        
        raf.seek(fileLen - 6);
        byte[] footer = new byte[6];
        raf.readFully(footer);

        if (footer[2] != (byte)0xff || footer[3] != (byte)0xff) {
            throw new Exception("no signature in file (no footer)");
        }

        final int commentSize = (footer[4] & 0xff) | ((footer[5] & 0xff) << 8);
        final int signatureStart = (footer[0] & 0xff) | ((footer[1] & 0xff) << 8);

        byte[] eocd = new byte[commentSize + 22];
        raf.seek(fileLen - (commentSize + 22));
        raf.readFully(eocd);

        if (eocd[0] != (byte)0x50 || eocd[1] != (byte)0x4b ||
            eocd[2] != (byte)0x05 || eocd[3] != (byte)0x06) {
            throw new Exception("no signature in file (bad footer)");
        }

        PKCS7 block = new PKCS7(new ByteArrayInputStream(eocd, commentSize+22-signatureStart, signatureStart));

        X509Certificate[] certificates = block.getCertificates();
        if (certificates == null || certificates.length == 0) {
            throw new Exception("signature contains no certificates");
        }
        X509Certificate cert = certificates[0];
        System.out.println("Cert Subject: " + cert.getSubjectDN());

        SignerInfo[] signerInfos = block.getSignerInfos();
        if (signerInfos == null || signerInfos.length == 0) {
            throw new Exception("signature contains no signedData");
        }
        SignerInfo signerInfo = signerInfos[0];

        long toRead = fileLen - commentSize - 2;
        raf.seek(0);
        byte[] fileBytes = new byte[(int)toRead];
        raf.readFully(fileBytes);
        raf.close();

        SignerInfo verifyResult = block.verify(signerInfo, fileBytes);

        if (verifyResult == null) {
            throw new Exception(">>> SIGNATURE DIGEST DID NOT MATCH! <<<");
        }
        System.out.println("=================================================");
        System.out.println(">>> OFFICIAL Java sun.security.pkcs.PKCS7.verify(): PASS 100%! <<<");
        System.out.println("=================================================");
    }
}
