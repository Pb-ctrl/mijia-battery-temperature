import cn.local.mijiabattery.Logic;
public class LogicCheck {
    static void require(boolean b, String s) { if (!b) throw new AssertionError(s); }
    public static void main(String[] args) {
        int[] high = {1, Logic.encode(35), Logic.encode(35)};
        int[] low = {0, Logic.encode(35), Logic.encode(35)};
        int[] any = {2, Logic.encode(35), Logic.encode(35)};
        require(Logic.crossing(high, 350, 351), "high crossing");
        require(!Logic.crossing(high, 351, 352), "no repeat while high");
        require(!Logic.crossing(high, 340, 350), "strict threshold");
        require(Logic.crossing(low, 350, 349), "low crossing");
        require(!Logic.crossing(low, 349, 348), "no repeat while low");
        require(Logic.crossing(any, 350, 351), "any temperature change");
        require(!Logic.crossing(any, 350, 350), "no unchanged dispatch");
        require(!Logic.crossing(any, Integer.MIN_VALUE, 350), "no initial dispatch");
        require(!Logic.satisfied(high, Integer.MIN_VALUE), "invalid sample rejected");
        require(!Logic.valid(new int[]{0, 35, 35}), "ordinary battery not marked");
        require(!Logic.valid(new int[]{0, 1035, 1040}), "malformed rejected");
        require(Logic.marked("b477e7e0-0000-4000-8000-000000000000"), "marker recognized");
        require(!Logic.marked("abcdef00-0000-4000-8000-000000000000"), "other ids unaffected");
        require(Logic.shouldTrigger(any, null, 350), "first valid any-change sample triggers");
        require(Logic.shouldTrigger(high, null, 351), "first satisfied threshold triggers");
        require(!Logic.shouldTrigger(high, null, 350), "first unsatisfied threshold stays quiet");
        require(!Logic.shouldTrigger(any, null, Integer.MIN_VALUE), "first invalid sample stays quiet");
        for (int d=1; d<=80; d++) require(Logic.encode(d)>100, "disabled framework must not match battery percent");
        require(!Logic.shouldTrigger(any, Integer.valueOf(350), 350), "later unchanged sample stays quiet");
        System.out.println("PASS: 18 scenario checks + 80 fail-closed temperature encodings");
    }
}
