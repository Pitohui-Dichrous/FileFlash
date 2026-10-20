import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Test-only screenshot QR decoder; uses the APK's existing ZXing dependency. */
public final class ReadQr {
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        System.out.print(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), java.util.Map.of(DecodeHintType.TRY_HARDER, true)).getText());
    }
}
