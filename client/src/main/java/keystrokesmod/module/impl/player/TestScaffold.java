package keystrokesmod.module.impl.player;

/**
 * Compatibility name for existing profiles and integrations. Full scaffold
 * implementation lives in MyauScaff.
 */
public class TestScaffold extends MyauScaff {
    public TestScaffold() {
        super();
    }

    @Override
    public String getName() {
        return "TestScaffold";
    }

    @Override
    public String getNameInHud() {
        return getName();
    }
}
