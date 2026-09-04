import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import java.io.PrintWriter;

public class Verify {
    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
        ClassReader cr = new ClassReader(bytes);
        CheckClassAdapter.verify(cr, false, new PrintWriter(System.out));
        System.out.println("verify() completed without throwing");
    }
}
