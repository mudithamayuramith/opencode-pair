package app.opencodepair;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Guards against launch-time crashes in the native shell. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MainActivityLaunchTest {

    @Test
    public void mainActivityStarts() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            Activity activity = controller.setup().get();
            assertNotNull(activity);
            assertNotNull(activity.getWindow().getDecorView());
        }
    }

    @Test
    public void addConnectionActivityStarts() {
        try (ActivityController<AddConnectionActivity> controller =
                     Robolectric.buildActivity(AddConnectionActivity.class)) {
            Activity activity = controller.setup().get();
            assertNotNull(activity);
            assertNotNull(activity.getWindow().getDecorView());
        }
    }
}
