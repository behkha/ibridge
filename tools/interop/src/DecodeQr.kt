import com.google.zxing.BinaryBitmap
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val img = ImageIO.read(File(args[0]))
    println(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))).text)
}
