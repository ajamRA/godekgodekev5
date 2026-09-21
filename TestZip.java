import java.io.File;
import java.util.zip.ZipFile;
import java.util.Enumeration;
import java.util.zip.ZipEntry;

public class TestZip {
    public static void main(String[] args) {
        for (String path : args) {
            System.out.println("Testing: " + path);
            try (ZipFile zf = new ZipFile(new File(path))) {
                System.out.println("Success! Entries: " + zf.size());
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    System.out.println("  Entry: " + entries.nextElement().getName());
                }
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
