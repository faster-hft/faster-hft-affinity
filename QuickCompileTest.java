/**
 * Quick compilation test to verify the library JAR is working
 * This is a minimal test that just imports and initializes the library
 */
public class QuickCompileTest {

    public static void main(String[] args) {
        System.out.println("🔧 Quick Compile Test for HFT Thread Affinity Library");
        System.out.println("=====================================================");

        try {
            // Test that we can import and use the library
            com.faster.affinity.factory.AffinityLibraryFactory factory =
                new com.faster.affinity.factory.AffinityLibraryFactory();

            com.faster.affinity.factory.AffinityLibrary lib =
                com.faster.affinity.factory.AffinityLibraryFactory.getDefault();

            if (lib.isInitialized()) {
                System.out.println("✅ Library compilation and initialization successful");
                System.out.println("✅ All imports resolved correctly");

                lib.shutdown();
                System.out.println("✅ Clean shutdown successful");
            } else {
                System.out.println("⚠️  Library compiled but failed to initialize");
            }

        } catch (NoClassDefFoundError e) {
            System.err.println("❌ Missing class: " + e.getMessage());
            System.err.println("Check that the JAR file contains all dependencies");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("❌ Compilation test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("🎉 Quick compile test passed!");
    }
}