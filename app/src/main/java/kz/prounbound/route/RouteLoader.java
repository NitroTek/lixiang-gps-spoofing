package kz.prounbound.route;

import android.content.res.AssetManager;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class RouteLoader {
    private RouteLoader() {}

    public static LoopRoute load(AssetManager assets, RouteCatalog.Entry entry) throws IOException {
        try (InputStream input = assets.open(entry.assetName())) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            List<double[]> points = new ArrayList<>();
            int segments = 0;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if ("trkseg".equals(parser.getName()) && ++segments > 1) {
                    throw new IOException("Expected one continuous track");
                }
                if ("trkpt".equals(parser.getName())) {
                    points.add(new double[]{
                            Double.parseDouble(parser.getAttributeValue(null, "lat")),
                            Double.parseDouble(parser.getAttributeValue(null, "lon"))});
                }
            }
            return new LoopRoute(points.toArray(new double[0][]), entry.closed);
        } catch (XmlPullParserException | IllegalArgumentException e) {
            throw new IOException("Invalid embedded route", e);
        }
    }
}
