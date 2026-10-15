import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Host-side ImageIO renderer, run in JDK source-file mode for all launcher densities in one batch. */
class LauncherIcon {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("expected artwork.png output-directory size:shape:file ...");
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        if (source == null || source.getWidth() != source.getHeight() || source.getWidth() > 4096) {
            throw new IllegalArgumentException("launcher artwork must be a readable square image, at most 4096px");
        }
        File output = new File(args[1]);
        for (int i = 2; i < args.length; ++i) {
            String[] spec = args[i].split(":", -1);
            if (spec.length != 3 || !(spec[1].equals("square") || spec[1].equals("round"))
                    || !spec[2].matches("[0-9]+\\.png")) {
                throw new IllegalArgumentException("invalid output specification: " + args[i]);
            }
            int size = Integer.parseInt(spec[0]);
            if (size < 1 || size > source.getWidth()) {
                throw new IllegalArgumentException("cannot upscale launcher artwork to " + size);
            }
            boolean round = spec[1].equals("round");
            int type = round || source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage image = new BufferedImage(size, size, type);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (round) {
                    g.setColor(Color.WHITE);
                    g.fill(new Ellipse2D.Double(0, 0, size, size));
                    g.setComposite(AlphaComposite.SrcIn);
                }
                g.drawImage(source, 0, 0, size, size, null);
            } finally {
                g.dispose();
            }
            if (!ImageIO.write(image, "png", new File(output, spec[2]))) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
        }
    }
}
