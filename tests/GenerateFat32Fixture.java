import io.github.drivedroidreborn.Fat32Image;

import java.io.File;

/** Creates test fixtures using the production formatter; no Android app runs. */
public final class GenerateFat32Fixture {
    public static void main(String[] arguments) throws Exception {
        File image = new File(arguments[0]);
        if (!image.createNewFile()) {
            throw new IllegalStateException("Refusing to overwrite an existing file.");
        }
        Fat32Image.create(image, Long.parseLong(arguments[1]) * Fat32Image.MIB,
                true, true, completed -> { });
    }
}
