package obf;

import java.util.Map;
import org.objectweb.asm.tree.ClassNode;

public interface Transform {
    String name();
    void apply(ObfContext ctx);
}
