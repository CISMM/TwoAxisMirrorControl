/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cismm;

import static cismm.MirrorControlForm.cur_mode;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Map;
import org.micromanager.utils.MathFunctions;
import org.micromanager.utils.ReportingUtils;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.filter.GaussianBlur;
import ij.process.ImageProcessor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.ImageUtils;
/**
 *
 * @author phsiao
 */
public class Util {
    //protected static AtomicBoolean is_freerun_running = new AtomicBoolean(false);
    //protected static AtomicBoolean is_calibration_running = new AtomicBoolean(false);
    //protected static AtomicBoolean stop_calibration_requested = new AtomicBoolean(false);
    
    //protected static AtomicBoolean stop_requested = new AtomicBoolean(false);
    //protected static AtomicBoolean is_running = new AtomicBoolean(false);
    public static AtomicBoolean is_stop_requested = new AtomicBoolean(false);
    
    public static String plugin_path() {
        return System.getProperty("user.dir") + 
               File.separator + "mmplugins" +
               File.separator;
    }
    
    
//    public static Process run_external_program(String prog, List<String> args, boolean wait_till_done) {
//        Process proc = null;
//        try {
//            args.add(0, prog);
//
//            ProcessBuilder pb = new ProcessBuilder(args);
//            proc = pb.start();
//            
//            if (wait_till_done) {
//                proc.waitFor();
//                proc.destroy();
//            }
//            
//        } catch (IOException ex) {
//            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
//        } catch (InterruptedException ex) {
//            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
//        }
//        return proc;
//    }
    public static Process run_external_program(String prog, List<String> args) {
        Process proc = null;
        try {
            args.add(0, prog);

            ProcessBuilder pb = new ProcessBuilder(args);
            proc = pb.start();
        } catch (IOException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
        return proc;
    }
    /**
     * Illuminate a spot at position x,y.
     */
    public static void set_voltage(String daq_str, double x, double y) {
        if (daq_str == null) {
            JOptionPane.showMessageDialog(null, "Calibration is required.");
            return;
        }
        if (x >= NI.min_v_x && x <= (NI.v_range_x + NI.min_v_x)
         && y >= NI.min_v_y && y <= (NI.v_range_y + NI.min_v_y))
        {
            final List<String> args = new ArrayList<String>();    
            args.add(daq_str);
            args.add(Double.toString(x));
            args.add(Double.toString(y));
      
//            run_external_program(plugin_path() + "two_ao_update.exe",
//                                 args, true);
                run_external_program(plugin_path() + "two_ao_update.exe",
                                 args);
        }
    }
    
    public static Point findMaxPixel(ImageProcessor proc) {

        // If there is no signal, return a point at (-2, -2)
        int[] min_max = ImageUtils.getMinMax(proc.getPixels());
        if (min_max[0] == 0) {
            min_max[0] = 1;
        }
        //JOptionPane.showMessageDialog(IJ.getImage().getWindow(), "min:" + String.valueOf(min_max[0])
        //        + " max:" + String.valueOf(min_max[1]));

        if ((int) min_max[1] / (int) min_max[0] < 5000) {
            return new Point(-2, -2);
        }

        int width = proc.getWidth();
        int imax = ImageUtils.findArrayMax(proc.getPixels());

        
        int y = imax / width;
        int x = imax % width;
        return new Point(x, y);
    }
    
    // Find the brightest spot in an ImageProcessor. The image is first blurred
    // and then the pixel with maximum intensity is returned.
    public static Point findPeak(ImageProcessor proc) {
        ImageProcessor blurImage = proc.duplicate(); 
        blurImage.setRoi((Roi) null);
        GaussianBlur blur = new GaussianBlur();
        blur.blurGaussian(blurImage, 5, 5, 0.01);
        
        Point x = findMaxPixel(blurImage);
        x.translate(1, 1);
        return x;
    }
    /**
     * Display a spot using the projection device, and return its current
     * location on the camera. Does not do sub-pixel localization, but could
     * (just would change its return type, most other code would be OK with
     * this)
     */
    public static Point measureSpotOnCamera(CMMCore core, ScriptInterface app,
                    String daq_str, Point2D.Double projectionPoint)
    {
        
        try {
            set_voltage(daq_str, projectionPoint.x, projectionPoint.y);
            core.snapImage();
            TaggedImage image = core.getTaggedImage();
            ImageProcessor proc1 = ImageUtils.makeMonochromeProcessor(image);
            Point maxPt = findPeak(proc1);

            app.displayImage(image);
            app.getSnapLiveWin().getImagePlus().setRoi(new PointRoi(maxPt.x, maxPt.y));

            return maxPt;
        } catch (Exception e) {
            ReportingUtils.showError(e);
            return null;
        }
    }
    /**
     * Illuminate a spot at ptSLM, measure its location on the camera, and add
     * the resulting point pair to the spotMap.
     */
    public static void measureAndAddToSpotMap(CMMCore core, ScriptInterface app,
            String daq_str, Map<Point2D.Double, Point2D.Double> spotMap, 
            Point2D.Double ptSLM)
    {
        
        Point ptCam = measureSpotOnCamera(core, app, daq_str, ptSLM);
        if (ptCam != null && ptCam.x >= 0) {
            Point2D.Double ptCamDouble = new Point2D.Double(ptCam.x, ptCam.y);
            spotMap.put(ptCamDouble, ptSLM);
        }
    }
    
    /**
     * Illuminates and images five control points near the center, and return an
     * affine transform mapping from image coordinates to phototargeter
     * coordinates.
     */
    public static AffineTransform generateLinearMapping(CMMCore core, ScriptInterface app,
           String daq_str)
    {
        
        double spacing = Math.min(NI.v_range_x, NI.v_range_y) / 10;  // use 10% of galvo/SLM range
        Map<Point2D.Double, Point2D.Double> p_to_v_map = new HashMap<Point2D.Double, Point2D.Double>();

        for (double i = NI.min_v_x; i <= NI.max_v_x; i += spacing) {
            for (double j = NI.min_v_y; j <= NI.max_v_y; j += spacing) {
                if (is_stop_requested.get()) {
                    return null;
                }
                measureAndAddToSpotMap(core, app, daq_str, p_to_v_map, new Point2D.Double(i, j));       
            }
        }
        
        try {
            return MathFunctions.generateAffineTransformFromPointPairs(p_to_v_map);
        } catch (Exception e) {
            throw new RuntimeException("Spots aren't detected as expected. Is DMD in focus and roughly centered in camera's field of view?");
        }
        
    }
    
    
    /**
     * Simple utility methods for points
     *
     * Adds a point to an existing polygon.
     */
    private static void addVertex(Polygon polygon, Point p) {
        polygon.addPoint(p.x, p.y);
    }
    /**
     * Converts a Point with double values for x,y to a point with x and y
     * rounded to the nearest integer.
     */
    public static Point toIntPoint(Point2D.Double pt) {
        return new Point((int) (0.5 + pt.x), (int) (0.5 + pt.y));
    }
    
    public static Point2D.Double toDoublePoint(Point pt) {
        return new Point2D.Double(pt.x, pt.y);
    }
    
    public static Map<Polygon, AffineTransform> generateNonlinearMapping(
            CMMCore core, ScriptInterface app, String daq_str,
            AffineTransform affine_map) {
      
        final int nGrid = 7;
        Point2D.Double slmPoint[][] = new Point2D.Double[1 + nGrid][1 + nGrid];
        Point2D.Double camPoint[][] = new Point2D.Double[1 + nGrid][1 + nGrid];

        final int padding = 25;
        final int cam_width = (int) core.getImageWidth() - padding * 2;
        final int cam_height = (int) core.getImageHeight() - padding * 2;
        final double cam_step_x = cam_width / nGrid;
        final double cam_step_y = cam_height / nGrid;

        for (int i = 0; i <= nGrid; i++) {
            for (int j = 0; j <= nGrid; j++) {
                if (is_stop_requested.get()) {
                    return null;
                }
                slmPoint[i][j] = (Point2D.Double) affine_map.transform(
                        new Point2D.Double(cam_step_x * i + padding,
                        cam_step_y * j + padding), null);
            }
        }
        // tabulate the camera spot at each of SLM grid points
        for (int i = 0; i <= nGrid; ++i) {
            for (int j = 0; j <= nGrid; ++j) {
                if (is_stop_requested.get()) {
                    return null;
                }
                
                Point spot = measureSpotOnCamera(core, app, daq_str, slmPoint[i][j]);
                if (spot != null) {
                    camPoint[i][j] = toDoublePoint(spot);
                }
                
            }
        }

        

        // now make a grid of (square) polygons (in camera's coordinate system)
        // and generate an affine transform for each of these square regions
        Map<Polygon, AffineTransform> bigMap = new HashMap<Polygon, AffineTransform>();
        for (int i = 0; i <= nGrid - 1; ++i) {
            for (int j = 0; j <= nGrid - 1; ++j) {
                if (is_stop_requested.get()) {
                   return null;
                }
                Polygon poly = new Polygon();
                addVertex(poly, toIntPoint(camPoint[i][j]));
                addVertex(poly, toIntPoint(camPoint[i][j + 1]));
                addVertex(poly, toIntPoint(camPoint[i + 1][j + 1]));
                addVertex(poly, toIntPoint(camPoint[i + 1][j]));

                Map<Point2D.Double, Point2D.Double> map = new HashMap<Point2D.Double, Point2D.Double>();
                map.put(camPoint[i][j], slmPoint[i][j]);
                map.put(camPoint[i][j + 1], slmPoint[i][j + 1]);
                map.put(camPoint[i + 1][j], slmPoint[i + 1][j]);
                map.put(camPoint[i + 1][j + 1], slmPoint[i + 1][j + 1]);
                double srcDX = Math.abs((camPoint[i + 1][j].x - camPoint[i][j].x)) / 4;
                double srcDY = Math.abs((camPoint[i][j + 1].y - camPoint[i][j].y)) / 4;
                double srcTol = Math.max(srcDX, srcDY);

                try {
                    AffineTransform transform = MathFunctions.generateAffineTransformFromPointPairs(map, srcTol, Double.MAX_VALUE);
                    bigMap.put(poly, transform);
                } catch (Exception e) {
                    ReportingUtils.logError("Bad cell in mapping.");
                }
            }
        }
        return bigMap;
    }
    
    public static Point2D.Double transformPoint(Map<Polygon, AffineTransform> mapping, Point2D.Double pt) {
        Set<Polygon> set = mapping.keySet();
        // First find out if the given point is inside a cell, and if so,
        // transform it with that cell's AffineTransform.

        for (Polygon poly : set) {
            if (poly.contains(pt)) {
                return (Point2D.Double) mapping.get(poly).transform(pt, null);
            }
        }

        // The point isn't inside any cell, so use the global mapping
        return (Point2D.Double) cur_mode.first_mapping.transform(pt, null);

        // The point isn't inside any cell, so search for the closest cell
        // and use the AffineTransform from that.
        /*
         double minDistance = Double.MAX_VALUE;
         Polygon bestPoly = null;
         for (Polygon poly : set) {
         double distance = meanPosition2D(getVertices(poly)).distance(pt.x, pt.y);
         if (minDistance > distance) {
         bestPoly = poly;
         minDistance = distance;
         }
         }
         if (bestPoly == null) {
         throw new RuntimeException("Unable to map point to device.");
         }
         return (Point2D.Double) mapping.get(bestPoly).transform(pt, null);
         */
    }
}
