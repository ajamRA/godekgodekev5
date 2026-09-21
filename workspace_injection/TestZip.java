import java.io.File;
import java.util.zip.ZipFile;
import java.util.Enumeration;
import java.util.zip.ZipEntry;

public class TestZip {
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "D:\\apps\\emas-ota\\workspace_injection\\output\\update.zip";
        try {
            System.out.println("Testing path: " + path);
            ZipFile zf = new ZipFile(new File(path));
            System.out.println("ZipFile opened! Entry count: " + zf.size());
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                System.out.println("  Entry: " + ze.getName() + " (" + ze.getSize() + " bytes)");
            }
            zf.close();
            System.out.println("SUCCESS!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
