import java.util.BitSet;
import java.util.HashMap;

public class Test {
    public static void main(String[] args) {
        HashMap<String, BitSet> map = new HashMap<String, BitSet>();
        BitSet bs = new BitSet();
        map.put("1", bs);
        System.out.println(map.get("1").get(0));
        bs.set(0);
        System.out.println(bs.get(0));
        System.out.println(map.get("1").get(0));
    }
}
