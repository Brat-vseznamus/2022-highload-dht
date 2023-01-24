package ok.dht.test.vihnin.code.inspector;

public interface Formatter<I, O> {
    O to(I input);
    I from(O output);
}
