/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cismm;

import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import static javax.swing.WindowConstants.HIDE_ON_CLOSE;
import mmcorej.CMMCore;
import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.JavaUtils;

/**
 *
 * @author phsiao
 */
public class MainFrame extends javax.swing.JFrame {

    public static class ExpConfig implements Serializable {
        
        public String mode_name = null;
        public String camera_name = null;
        public String daq_dev_str = null;
        double um_per_pix = 0;
        AffineTransform first_mapping = null;
        
        int center_x = -1;
        int center_y = -1;
        
        HashMap<Polygon, AffineTransform> poly_mapping = null;
    } 
    // Class variables
    static final String version_str = "1.0";
    private ScriptInterface app;
    private CMMCore core;
    public static ExpConfig cur_config = null;
    
    // List has tab names in order
    List<String> calibration_name_array = new ArrayList<String>();
    
    Map<String, DataPanel> calibration_panel_map = new HashMap<String, DataPanel>();
    Map<String, ExpConfig> config_map = new HashMap<String, ExpConfig>();
    /**
     * Creates new form MainFrame
     */
    public MainFrame(CMMCore core_, ScriptInterface app_) {
        initComponents();     
        app = app_;
        core = core_;
        
        TIRFPanel tirf_panel = new TIRFPanel(core, app);
        PhotoactivationPanel pa_panel = new PhotoactivationPanel(core, app);
        
        this.add_panel("TIRF", tirf_panel, true);
        this.add_panel("Photoactivation", pa_panel, true);
        
        // Calibration panel has to be the last
        CalibrationPanel calibration_panel = new CalibrationPanel(core, app, 
                calibration_name_array, config_map);
        this.add_panel("Calibration", calibration_panel, false);
        
        // Read keys from panel_map and use them to load calibrations.
        load_calibrations(calibration_panel_map, config_map);
        
        // Send cablibration to each panel
        disptach_calibrations();
        
        //tabbed_panel_ui.setenabsetEnabledAt(1, false);    
        this.setTitle("Microscopy-Bridge " + version_str);
       
        //tirf_loops_ui.setModel(tirf_loops_model);
        this.setDefaultCloseOperation(HIDE_ON_CLOSE);
    }
    
    private void add_panel(String key, DataPanel panel, boolean has_calibration) {
        this.tabbed_panel_ui.add(key, panel);
        if (has_calibration) {
            calibration_panel_map.put(key, panel);
            calibration_name_array.add(key);
        }
    }
    
    private void disptach_calibrations()
    {
        for(String key : calibration_panel_map.keySet()) {
            update_calibration(key);
        }
    }
    
    public void update_calibration(String key) {
        DataPanel panel = calibration_panel_map.get(key);
        panel.set_config(config_map.get(key));
    }
    /*
    private void update_cur_config_based_on_tab() {
        int tab_ind = this.tabbed_panel_ui.getSelectedIndex();
        if (tab_ind < 2) {
            cur_config = config_map.get(mode_str_array.get(tab_ind));
        }
    }
    */
    private static Preferences getCalibrationNode() {
        try {
            Preferences p = Preferences.userNodeForPackage(DualAxisMirrorPlugin.class)
            //Preferences p = Preferences.systemNodeForPackage(DualAxisMirrorPlugin.class)
                    .node("calibration");
                    
            p.flush();
            return p;
        } catch (NullPointerException npe) {
            return null;
        } catch (BackingStoreException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }
    
    private void load_calibrations(Map<String, DataPanel> panel_map,
                                   Map<String, ExpConfig> config_map) {       
        Preferences prefs = getCalibrationNode();
        if (prefs == null) {
            return;
        }

        //String nodeStr = prefs.toString();

        for (String key : panel_map.keySet()) {
            ExpConfig m = (ExpConfig) JavaUtils.getObjectFromPrefs(prefs, key, new ExpConfig());
            config_map.put(key, m);
            
//            if (m.first_mapping != null) {
//                mark_calibration_label(m.mode_name);
//            }
            
//            if (m.mode_name != null && m.mode_name.equals(mode_str_array.get(0))) {
//                original_center_x_ui.setText(Integer.toString(m.center_x));
//                original_center_y_ui.setText(Integer.toString(m.center_y));
//                center_input_x_ui.setValue(Integer.valueOf(m.center_x));
//                center_input_y_ui.setValue(Integer.valueOf(m.center_y));
//            }
        }    
    }
    
    public static void save_calibration_to_disk(ExpConfig config) {
        Preferences p = getCalibrationNode();
        JavaUtils.putObjectInPrefs(p, config.mode_name, config);
    }
    
    public void cleanup() {
        NI.cleanup();
    }
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        tabbed_panel_ui = new javax.swing.JTabbedPane();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(tabbed_panel_ui, javax.swing.GroupLayout.DEFAULT_SIZE, 537, Short.MAX_VALUE)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap(74, Short.MAX_VALUE)
                .addComponent(tabbed_panel_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 284, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTabbedPane tabbed_panel_ui;
    // End of variables declaration//GEN-END:variables
}
