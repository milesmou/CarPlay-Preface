import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData;

/** Copies real fields synchronously before the vendor reuses its data object. */
final class Preface95Guidance {
    final int type;
    final int icon;
    final int round;
    final String distance;
    final String road;
    final long receivedAt;
    private final Bitmap suppliedIcon;
    private final byte[] online;
    private final int onlineWidth;
    private final int onlineHeight;

    Preface95Guidance(GuideInfoProtocolData data, long now) {
        type = data.getType();
        icon = data.getIcon();
        round = data.getRoundAboutNum();
        String formatted = clean(data.getSegRemainDisAuto());
        distance = !formatted.isEmpty() ? formatted : data.getSegRemainDis() >= 0
                ? data.getSegRemainDis() + " 米" : "";
        road = clean(data.getNextRoadName());
        receivedAt = now;
        Bitmap bitmap = data.getTurnIconBitmap();
        suppliedIcon = bitmap != null && !bitmap.isRecycled() &&
                bitmap.getWidth() <= 1024 && bitmap.getHeight() <= 1024
                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
        onlineWidth = data.getOnlineIconWidth();
        onlineHeight = data.getOnlineIconHeight();
        byte[] bytes = data.getOnlineIcon();
        long expected = 54L + (long) onlineWidth * onlineHeight * 4L;
        online = onlineWidth > 0 && onlineHeight > 0 && onlineWidth <= 1024 &&
                onlineHeight <= 1024 && bytes != null && bytes.length == expected
                ? bytes.clone() : null;
    }

    boolean navigates() { return type == 0 || type == 1; }

    Bitmap makeArrow(Context context) {
        if (suppliedIcon != null && !suppliedIcon.isRecycled()) {
            return suppliedIcon.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (online != null) {
            int[] pixels = ob.b(online);
            if (pixels != null && pixels.length == onlineWidth * onlineHeight) {
                return Bitmap.createBitmap(pixels, onlineWidth, onlineHeight, Bitmap.Config.ARGB_8888);
            }
        }
        // Exact filename rules reviewed in 9.5 ob.i. Resolve its packaged
        // resources without ob.h's unconditional System.gc() on each update.
        int resource = icon;
        if (round > 0 && (icon == 11 || icon == 12)) resource = round + 49;
        else if (round > 0 && (icon == 17 || icon == 18)) resource = round + 59;
        else if (icon == 65 || icon == 66) resource = icon + 5;
        if (resource < 0 || resource > 999) return null;
        int id = context.getResources().getIdentifier("sou" + resource + "_night_a530",
                "drawable", context.getPackageName());
        return id == 0 ? null : BitmapFactory.decodeResource(context.getResources(), id);
    }

    void release() {
        if (suppliedIcon != null && !suppliedIcon.isRecycled()) suppliedIcon.recycle();
    }

    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
