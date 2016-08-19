package cismm;

import ij.IJ;
import ij.gui.PointRoi;
import ij.process.ImageProcessor;
import java.awt.Color;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
//import java.util.prefs.Preferences;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;
import static javax.swing.WindowConstants.HIDE_ON_CLOSE;
import mmcorej.CMMCore;
import mmcorej.DeviceType;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.ImageUtils;
import org.micromanager.utils.JavaUtils;
import org.micromanager.utils.MathFunctions;
import org.micromanager.utils.ReportingUtils;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author phsiao
 */
public class MirrorControlForm extends javax.swing.JFrame {

    public static class ExpMode implements Serializable {
        
        public String mode_name = null;
        public String camera_name = null;
        public String daq_dev_str = null;
        double um_per_pix = 0;
        AffineTransform first_mapping = null;
        
        int center_x = -1;
        int center_y = -1;
        
        HashMap<Polygon, AffineTransform> poly_mapping = null;
    }
    
    public static class TIRFCircle implements Serializable {
        
        List<String> volts = null;
        int radius_um = 0;
        double circle_frequency = 0;
        int center_x = -1;
        int center_y = -1;
        
        @Override
        public String toString() {
            return Integer.toString(radius_um) + "um, " +
                   Double.toString(circle_frequency) + "Hz";
        }  
    }
    /**
     * Creates new form MirrorControlForm
     */
    private ScriptInterface app_;
    private CMMCore core_;
    AtomicBoolean isRunning_ = new AtomicBoolean(false);
    AtomicBoolean stopRequested_ = new AtomicBoolean(false);
    private double min_v_x = -10;
    private double max_v_x = 10;
    private double min_v_y = -10;
    private double max_v_y = 10;
    
    private double v_range_x = max_v_x - min_v_x;
    private double v_range_y = max_v_y - min_v_y;
    private List<String> daq_bin_list_;
    
    private Process daq_proc = null;
    private ExpMode cur_mode = null;
    
    
    Map<String, ExpMode> mode_map = new HashMap<String, ExpMode>();
    
    // tirf_loops contains TIRFCircle objects
    // tirf_loops_model contains strings shown on GUI
    // To update GUI, one needs to update tirf_loops_model from tirf_loops,
    // and then display the updated tirf_loops_model string.
    List<TIRFCircle> tirf_loops = new ArrayList<TIRFCircle>();
    DefaultListModel tirf_loops_model = new DefaultListModel();
    
    BufferedWriter writer_to_daq = null;

    
    // The order of the strings must match the order of the tabs on the GUI. 
    List<String> mode_str_array = Arrays.asList("TIRF",
            "PHOTOBLEACHING");
    
    private void fill_camera_list() {
        StrVector devices = core_.getLoadedDevicesOfType(DeviceType.CameraDevice);
        camera_name_ui.setModel(new DefaultComboBoxModel(devices.toArray()));
    }

    private void mark_calibration_label(String calibration_name) {
        if (calibration_name.equals(mode_str_array.get(0))) {
                    tirf_calibration_sign.setText("FOUND");
                    tirf_calibration_sign.setForeground(Color.green);
                    tirf_calibration_sign.setBackground(Color.black);
                    tirf_calibration_sign.setOpaque(true);
                } else if (calibration_name.equals(mode_str_array.get(0))) {
                    photobleaching_calibration_sign.setText("FOUND");
                    photobleaching_calibration_sign.setForeground(Color.green);
                    photobleaching_calibration_sign.setBackground(Color.black);
                    photobleaching_calibration_sign.setOpaque(true);
                } else {}
    }
    
    private void fill_mode_map() {       
        Preferences prefs = getCalibrationNode();
        if (prefs == null) {
            return;
        }

        //String nodeStr = prefs.toString();

        for (String key : mode_str_array) {
            ExpMode m = (ExpMode) JavaUtils.getObjectFromPrefs(prefs, key, new ExpMode());
            mode_map.put(key, m);
            
            if (m.first_mapping != null) {
                mark_calibration_label(m.mode_name);
            }
            
            if (m.mode_name != null && m.mode_name.equals(mode_str_array.get(0))) {
                original_center_x_ui.setText(Integer.toString(m.center_x));
                original_center_y_ui.setText(Integer.toString(m.center_y));
                center_input_x_ui.setValue(Integer.valueOf(m.center_x));
                center_input_y_ui.setValue(Integer.valueOf(m.center_y));
            }
        }    
    }
    
    private void update_tirf_model_ui() {
        tirf_loops_ui.setModel(tirf_loops_model);
    }
    
    private void load_tirf_strings() {
        for (TIRFCircle t: tirf_loops) {
            tirf_loops_model.addElement(t.toString());
        }
    }
    private void load_tirf_loops_model_to_ui() {
        load_tirf_strings();
        update_tirf_model_ui();
    }

    public MirrorControlForm(CMMCore core, ScriptInterface app, List<String> daq_bin_list) {
        //public MirrorControlForm(List<String> daq_bain_list) {
        initComponents();
        light_mode_drop.setModel(new DefaultComboBoxModel(mode_str_array.toArray()));
        ((JSpinner.DefaultEditor)input_volt_x_ui.getEditor()).getTextField().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e){
                if (e.getKeyCode() == KeyEvent.VK_ENTER){
                    displaySpot((Double)input_volt_x_ui.getValue(), 
                                (Double)input_volt_y_ui.getValue());
                }
            }
        });
        
        ((JSpinner.DefaultEditor)input_volt_y_ui.getEditor()).getTextField().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e){
                if (e.getKeyCode() == KeyEvent.VK_ENTER){
                    displaySpot((Double)input_volt_x_ui.getValue(), 
                                (Double)input_volt_y_ui.getValue());
                }
            }
        });

        this.setDefaultCloseOperation(HIDE_ON_CLOSE);

        core_ = core;
        app_ = app;

        daq_bin_list_ = daq_bin_list;
        fill_mode_map();
        fill_camera_list();
        load_tirf_loops_model_to_ui();
        update_cur_mode_based_on_tab();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane2 = new javax.swing.JScrollPane();
        jTable1 = new javax.swing.JTable();
        tabbed_panel = new javax.swing.JTabbedPane();
        jPanel1 = new javax.swing.JPanel();
        jLabel17 = new javax.swing.JLabel();
        jLabel19 = new javax.swing.JLabel();
        jLabel24 = new javax.swing.JLabel();
        jPanel4 = new javax.swing.JPanel();
        jLabel26 = new javax.swing.JLabel();
        jLabel28 = new javax.swing.JLabel();
        jLabel21 = new javax.swing.JLabel();
        circle_radius_ui = new javax.swing.JSpinner();
        circle_samples_ui = new javax.swing.JSpinner();
        circle_frequency_ui = new javax.swing.JSpinner();
        freerun_button = new javax.swing.JButton();
        jLabel22 = new javax.swing.JLabel();
        jLabel18 = new javax.swing.JLabel();
        original_center_x_ui = new javax.swing.JLabel();
        jLabel29 = new javax.swing.JLabel();
        original_center_y_ui = new javax.swing.JLabel();
        jLabel31 = new javax.swing.JLabel();
        center_input_x_ui = new javax.swing.JSpinner();
        jLabel32 = new javax.swing.JLabel();
        center_input_y_ui = new javax.swing.JSpinner();
        jPanel5 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        tirf_loops_ui = new javax.swing.JList();
        jButton10 = new javax.swing.JButton();
        jButton11 = new javax.swing.JButton();
        jButton12 = new javax.swing.JButton();
        add_circle_ui = new javax.swing.JButton();
        input_volt_x_ui = new javax.swing.JSpinner();
        input_volt_y_ui = new javax.swing.JSpinner();
        submit_circles_ui = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        point_shoot_button = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        jSpinner1 = new javax.swing.JSpinner();
        jLabel7 = new javax.swing.JLabel();
        jButton2 = new javax.swing.JButton();
        jButton3 = new javax.swing.JButton();
        jLabel8 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jLabel10 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        jLabel12 = new javax.swing.JLabel();
        jSpinner2 = new javax.swing.JSpinner();
        jLabel13 = new javax.swing.JLabel();
        jSpinner3 = new javax.swing.JSpinner();
        jLabel14 = new javax.swing.JLabel();
        jButton4 = new javax.swing.JButton();
        jSeparator1 = new javax.swing.JSeparator();
        point_shoot_x = new javax.swing.JTextField();
        point_shoot_y = new javax.swing.JTextField();
        jPanel2 = new javax.swing.JPanel();
        jPanel7 = new javax.swing.JPanel();
        camera_name_ui = new javax.swing.JComboBox();
        jLabel15 = new javax.swing.JLabel();
        jLabel33 = new javax.swing.JLabel();
        jLabel34 = new javax.swing.JLabel();
        x_axis_ui = new javax.swing.JTextField();
        y_axis_ui = new javax.swing.JTextField();
        jLabel23 = new javax.swing.JLabel();
        um_per_pix_ui = new javax.swing.JSpinner();
        jLabel25 = new javax.swing.JLabel();
        light_mode_drop = new javax.swing.JComboBox();
        jLabel35 = new javax.swing.JLabel();
        calibration_button = new javax.swing.JButton();
        reset_daq_ui = new javax.swing.JButton();
        jLabel27 = new javax.swing.JLabel();
        dev_name_ui = new javax.swing.JTextField();
        jLabel4 = new javax.swing.JLabel();
        jLabel16 = new javax.swing.JLabel();
        tirf_calibration_sign = new javax.swing.JLabel();
        photobleaching_calibration_sign = new javax.swing.JLabel();

        jTable1.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {null, null, null, null},
                {null, null, null, null},
                {null, null, null, null},
                {null, null, null, null}
            },
            new String [] {
                "Title 1", "Title 2", "Title 3", "Title 4"
            }
        ));
        jScrollPane2.setViewportView(jTable1);

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        tabbed_panel.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                tabbed_panelStateChanged(evt);
            }
        });

        jLabel17.setText("X:");

        jLabel19.setText("Y:");

        jLabel24.setText("(-10 to 10) volt");

        jPanel4.setBorder(javax.swing.BorderFactory.createTitledBorder("New Circle"));

        jLabel26.setText("Radius (um):");

        jLabel28.setText("# of samples:");

        jLabel21.setText("Circle Frequency:");

        circle_radius_ui.setModel(new javax.swing.SpinnerNumberModel(Integer.valueOf(2800), Integer.valueOf(0), null, Integer.valueOf(1)));

        circle_samples_ui.setModel(new javax.swing.SpinnerNumberModel(Integer.valueOf(200), Integer.valueOf(0), null, Integer.valueOf(1)));

        circle_frequency_ui.setModel(new javax.swing.SpinnerNumberModel(Double.valueOf(30.0d), Double.valueOf(0.1d), null, Double.valueOf(1.0d)));

        freerun_button.setText("Free Run");
        freerun_button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                freerun_buttonActionPerformed(evt);
            }
        });

        jLabel22.setText("Hz");

        jLabel18.setText("Detected Center:");

        original_center_x_ui.setText("-1");

        jLabel29.setText(",");

        original_center_y_ui.setText("-1");

        jLabel31.setText("CenterX:");

        center_input_x_ui.setModel(new javax.swing.SpinnerNumberModel());

        jLabel32.setText("CenterY:");

        center_input_y_ui.setModel(new javax.swing.SpinnerNumberModel());

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel4Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jLabel21)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(circle_frequency_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel22)
                        .addGap(18, 18, 18)
                        .addComponent(freerun_button))
                    .addGroup(jPanel4Layout.createSequentialGroup()
                        .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jLabel28)
                                    .addComponent(jLabel26)
                                    .addComponent(jLabel18))
                                .addGap(5, 5, 5)
                                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(jPanel4Layout.createSequentialGroup()
                                        .addComponent(original_center_x_ui)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                        .addComponent(jLabel29, javax.swing.GroupLayout.PREFERRED_SIZE, 8, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                        .addComponent(original_center_y_ui))
                                    .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                        .addComponent(circle_samples_ui, javax.swing.GroupLayout.Alignment.LEADING)
                                        .addComponent(circle_radius_ui, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, 77, Short.MAX_VALUE))))
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addComponent(jLabel31)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(center_input_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 62, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel32)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(center_input_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel4Layout.createSequentialGroup()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel18)
                    .addComponent(original_center_x_ui)
                    .addComponent(jLabel29)
                    .addComponent(original_center_y_ui))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 14, Short.MAX_VALUE)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel31)
                    .addComponent(center_input_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel32)
                    .addComponent(center_input_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel26)
                    .addComponent(circle_radius_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel28)
                    .addComponent(circle_samples_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel4Layout.createSequentialGroup()
                        .addGap(3, 3, 3)
                        .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel21)
                            .addComponent(circle_frequency_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel22)))
                    .addComponent(freerun_button, javax.swing.GroupLayout.Alignment.TRAILING))
                .addContainerGap())
        );

        jPanel5.setBorder(javax.swing.BorderFactory.createTitledBorder("Circle Loops (radius in um)"));

        tirf_loops_ui.setModel(new javax.swing.AbstractListModel() {
            String[] strings = { "Item 1", "Item 2", "Item 3" };
            public int getSize() { return strings.length; }
            public Object getElementAt(int i) { return strings[i]; }
        });
        jScrollPane1.setViewportView(tirf_loops_ui);

        jButton10.setText("Delete");

        jButton11.setText("Move Up");

        jButton12.setText("Move Down");

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButton10, javax.swing.GroupLayout.PREFERRED_SIZE, 89, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton11, javax.swing.GroupLayout.PREFERRED_SIZE, 89, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton12))
                .addGap(6, 6, 6))
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addComponent(jButton10)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButton11)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButton12)))
                .addGap(0, 0, Short.MAX_VALUE))
        );

        add_circle_ui.setText("Add Circle to Loop");
        add_circle_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                add_circle_uiActionPerformed(evt);
            }
        });

        input_volt_x_ui.setModel(new javax.swing.SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.01d));

        input_volt_y_ui.setModel(new javax.swing.SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.01d));

        submit_circles_ui.setText("Submit Circles");
        submit_circles_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                submit_circles_uiActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, 259, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jPanel5, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(submit_circles_ui)))
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(jLabel17)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(input_volt_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel19)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(input_volt_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel24))
                    .addComponent(add_circle_ui))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jPanel5, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(submit_circles_ui)
                        .addGap(32, 32, 32))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)))
                .addComponent(add_circle_ui)
                .addGap(18, 18, 18)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel19)
                    .addComponent(jLabel24)
                    .addComponent(jLabel17)
                    .addComponent(input_volt_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(input_volt_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(47, 47, 47))
        );

        tabbed_panel.addTab("TIRF", jPanel1);

        point_shoot_button.setText("Point and shoot");
        point_shoot_button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                point_shoot_buttonActionPerformed(evt);
            }
        });

        jLabel1.setText("Turn off light after:");

        jLabel2.setText("Current target:");

        jLabel3.setText("512");

        jLabel5.setText("320");

        jLabel6.setText("(To phototarget, Ctrl + click on the image)");

        jLabel7.setText("ms");

        jButton2.setText("ROI Manager >>");

        jButton3.setText("Set ROIs");

        jLabel8.setText("No ROIs submitted");

        jLabel9.setText("x:");

        jLabel10.setText("y:");

        jLabel11.setText("Spot dwell time:");

        jLabel12.setText("Loop");

        jSpinner2.setValue(1);

        jLabel13.setText("times");

        jSpinner3.setValue(1);

        jLabel14.setText("ms");

        jButton4.setText("Run ROIs now!");

        jSeparator1.setOrientation(javax.swing.SwingConstants.VERTICAL);

        point_shoot_x.setText("0");

        point_shoot_y.setText("0");

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(point_shoot_button)
                            .addComponent(jLabel2)
                            .addGroup(jPanel3Layout.createSequentialGroup()
                                .addComponent(jLabel9)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jLabel3)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel10)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jLabel5))
                            .addGroup(jPanel3Layout.createSequentialGroup()
                                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(jPanel3Layout.createSequentialGroup()
                                        .addComponent(point_shoot_x)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(point_shoot_y, javax.swing.GroupLayout.PREFERRED_SIZE, 67, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addGap(18, 18, 18))
                                    .addGroup(jPanel3Layout.createSequentialGroup()
                                        .addComponent(jLabel1)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(jSpinner1, javax.swing.GroupLayout.PREFERRED_SIZE, 48, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                                .addComponent(jLabel7)))
                        .addGap(53, 53, 53))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel6)
                        .addGap(18, 18, 18)))
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 15, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel12)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jSpinner2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel13)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jButton3)
                            .addComponent(jButton2)
                            .addComponent(jLabel8))
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jButton4)
                        .addGap(131, 155, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel11)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jSpinner3, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel14)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jButton2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jButton3)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel8)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel12)
                            .addComponent(jSpinner2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel13))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel11)
                            .addComponent(jSpinner3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel14))
                        .addGap(18, 18, 18)
                        .addComponent(jButton4))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jLabel6)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel3)
                            .addComponent(jLabel5)
                            .addComponent(jLabel9)
                            .addComponent(jLabel10))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel1)
                            .addComponent(jSpinner1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel7))
                        .addGap(18, 18, 18)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(point_shoot_x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(point_shoot_y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(5, 5, 5)
                        .addComponent(point_shoot_button)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup()
                .addGap(0, 45, Short.MAX_VALUE)
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 183, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(35, 35, 35))
        );

        tabbed_panel.addTab("Photobleaching", jPanel3);

        jPanel7.setBorder(javax.swing.BorderFactory.createTitledBorder("Setup"));

        jLabel15.setText("Camera:");

        jLabel33.setText("X Axis:");

        jLabel34.setText("Y Axis:");

        x_axis_ui.setText("Dev1/ao2");

        y_axis_ui.setText("Dev1/ao3");

        jLabel23.setText("Pixel Size:");

        um_per_pix_ui.setModel(new javax.swing.SpinnerNumberModel(Double.valueOf(5.2d), Double.valueOf(0.0d), null, Double.valueOf(0.1d)));

        jLabel25.setText("um");

        javax.swing.GroupLayout jPanel7Layout = new javax.swing.GroupLayout(jPanel7);
        jPanel7.setLayout(jPanel7Layout);
        jPanel7Layout.setHorizontalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel15)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(camera_name_ui, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel33)
                        .addGap(18, 18, 18)
                        .addComponent(x_axis_ui, javax.swing.GroupLayout.DEFAULT_SIZE, 103, Short.MAX_VALUE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel23)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(um_per_pix_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 72, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel25)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jLabel34)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(y_axis_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 103, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        jPanel7Layout.setVerticalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(camera_name_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel15)
                    .addComponent(jLabel23)
                    .addComponent(um_per_pix_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel25))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel33)
                    .addComponent(x_axis_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel34)
                    .addComponent(y_axis_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jLabel35.setText("Calibration for");

        calibration_button.setText("Calibrate Now!");
        calibration_button.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                calibration_buttonActionPerformed(evt);
            }
        });

        reset_daq_ui.setText("Reset DAQ");
        reset_daq_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                reset_daq_uiActionPerformed(evt);
            }
        });

        jLabel27.setText("on");

        dev_name_ui.setText("Dev1");

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addComponent(jLabel35)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(light_mode_drop, javax.swing.GroupLayout.PREFERRED_SIZE, 104, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jPanel7, javax.swing.GroupLayout.PREFERRED_SIZE, 329, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(calibration_button)
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addComponent(reset_daq_ui)
                        .addGap(10, 10, 10)
                        .addComponent(jLabel27)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(dev_name_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 54, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(169, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(light_mode_drop, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel35))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(calibration_button)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 74, Short.MAX_VALUE)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(reset_daq_ui)
                    .addComponent(jLabel27)
                    .addComponent(dev_name_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        jPanel7.getAccessibleContext().setAccessibleName("Calibration Setup");

        tabbed_panel.addTab(" Hardware Calibration", jPanel2);

        jLabel4.setText("TIRF Calibration:");

        jLabel16.setText("Photobleaching Calibration:");

        tirf_calibration_sign.setForeground(new java.awt.Color(255, 0, 0));
        tirf_calibration_sign.setText("N/A");

        photobleaching_calibration_sign.setForeground(java.awt.Color.red);
        photobleaching_calibration_sign.setText("N/A");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(tabbed_panel, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel16)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(photobleaching_calibration_sign)
                                .addGap(0, 0, Short.MAX_VALUE)))
                        .addContainerGap())
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jLabel4)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(tirf_calibration_sign)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel4)
                    .addComponent(tirf_calibration_sign))
                .addGap(15, 15, 15)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel16)
                    .addComponent(photobleaching_calibration_sign))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(tabbed_panel, javax.swing.GroupLayout.PREFERRED_SIZE, 291, Short.MAX_VALUE)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    /*
     private String getPinStr(LightMode mode) {
     switch(mode) {
     case TIRF:
     return pin_array;
     case PHOTOBLEACHING:
     return pb_pin;
     default:
     break;
     }
     return null;      
     }
     */
    private static Point[] getVertices(Polygon polygon) {
        Point vertices[] = new Point[polygon.npoints];
        for (int i = 0; i < polygon.npoints; ++i) {
            vertices[i] = new Point(polygon.xpoints[i], polygon.ypoints[i]);
        }
        return vertices;
    }

    /**
     * Gets the vectorial mean of an array of Points.
     */
    private static Point2D.Double meanPosition2D(Point[] points) {
        double xsum = 0;
        double ysum = 0;
        int n = points.length;
        for (int i = 0; i < n; ++i) {
            xsum += points[i].x;
            ysum += points[i].y;
        }
        return new Point2D.Double(xsum / n, ysum / n);
    }

    private Point2D.Double transformPoint(Map<Polygon, AffineTransform> mapping, Point2D.Double pt) {
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
    private static Point toIntPoint(Point2D.Double pt) {
        return new Point((int) (0.5 + pt.x), (int) (0.5 + pt.y));
    }

    /**
     * Converts a Point with integer values to a Point with x and y doubles.
     */
    private static Point2D.Double toDoublePoint(Point pt) {
        return new Point2D.Double(pt.x, pt.y);
    }

    /**
     * Illuminate a spot at position x,y.
     */
    private void displaySpot(double x, double y) {
        if (cur_mode.daq_dev_str == null) {
            JOptionPane.showMessageDialog(null, "Need to cJOptionalibrate first");
            return;
        }
        if (x >= min_v_x && x <= (v_range_x + min_v_x)
                && y >= min_v_y && y <= (v_range_y + min_v_y))
                 {
            //String pin_str = getPinStr(LightMode.TIRF);
            two_ao_update(cur_mode.daq_dev_str, Double.toString(x), Double.toString(y));
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

        if ((int) min_max[1] / (int) min_max[0] < 5) {
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
    private static Point findPeak(ImageProcessor proc) {
        ImageProcessor blurImage = proc.duplicate();
        
        /*
        blurImage.setRoi((Roi) null);
        GaussianBlur blur = new GaussianBlur();
        blur.blurGaussian(blurImage, 3, 3, 0.01);
        */
        //showProcessor("findPeak",proc);
        //Point x = ImageUtils.findMaxPixel(blurImage);
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
    private Point measureSpotOnCamera(Point2D.Double projectionPoint, boolean addToAlbum) {
        if (stopRequested_.get()) {
            return null;
        }
        try {

            displaySpot(projectionPoint.x, projectionPoint.y);
            core_.snapImage();
            TaggedImage image = core_.getTaggedImage();
            ImageProcessor proc1 = ImageUtils.makeMonochromeProcessor(image);
            Point maxPt = findPeak(proc1);
            // JonD: should use the exposure that the user has set to avoid hardcoding a value;
            // if the user wants a different exposure time for calibration it's easy to specify
            // => commenting out next two lines
            // long originalExposure = dev_.getExposure();
            // dev_.setExposure(500000);

            // NS: Timing between displaySpot and snapImage is critical
            // we have no idea how fast the device will respond
            // if we add "dev_.waitForDevice(), then the RAPP UGA-40 will already have ended
            // its exposure before returning control
            // For now, wait for a user specified delay

            //int delayMs = Integer.parseInt(delayField_.getText());
            //Thread.sleep(delayMs);

            //core_.snapImage();
            // NS: just make sure to wait until the spot is no longer displayed
            // JonD: time to wait is simply the exposure time less any delay

            // Maybe we need this??
            // Thread.sleep((int) (dev_.getExposure()/1000) - delayMs);

            // JonD: see earlier comment => commenting out next line
            // dev_.setExposure(originalExposure);
            //TaggedImage taggedImage2 = core_.getTaggedImage();
            //ImageProcessor proc2 = ImageUtils.makeMonochromeProcessor(taggedImage2);
            //app_.displayImage(taggedImage2);
            // saving images to album is useful for debugging
            // TODO figure out why this doesn't work towards the end; maybe limitation on # of images in album
            // if (addToAlbum) {
            //    app_.addToAlbum(taggedImage2);
            // }
            //ImageProcessor diffImage = ImageUtils.subtractImageProcessors(proc2.convertToFloatProcessor(), proc1.convertToFloatProcessor());
            //app_.closeAcquisitionWindow(app_.getSnapLiveWin().getName());
            //app_.closeAllAcquisitions();


            app_.displayImage(image);
            app_.getSnapLiveWin().getImagePlus().setRoi(new PointRoi(maxPt.x, maxPt.y));


            // NS: what is this second sleep good for????
            // core_.sleep(500);
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
    private void measureAndAddToSpotMap(Map<Point2D.Double, Point2D.Double> spotMap,
            Point2D.Double ptSLM) {
        Point ptCam = measureSpotOnCamera(ptSLM, false);
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
    private AffineTransform generateLinearMapping() {
        //double centerX = v_range_x / 2 + min_v_x;
        //double centerY = v_range_y / 2 + min_v_y;
        double spacing = Math.min(v_range_x, v_range_y) / 10;  // use 10% of galvo/SLM range
        Map<Point2D.Double, Point2D.Double> big_map = new HashMap<Point2D.Double, Point2D.Double>();

        for (double i = min_v_x; i <= max_v_x; i += spacing) {
            for (double j = min_v_y; j <= max_v_y; j += spacing) {
                measureAndAddToSpotMap(big_map, new Point2D.Double(i, j));
            }
        }
        /*
         measureAndAddToSpotMap(spotMap, new Point2D.Double(centerX, centerY));
         measureAndAddToSpotMap(spotMap, new Point2D.Double(centerX, centerY + spacing));
         measureAndAddToSpotMap(spotMap, new Point2D.Double(centerX + spacing, centerY));
         measureAndAddToSpotMap(spotMap, new Point2D.Double(centerX, centerY - spacing));
         measureAndAddToSpotMap(spotMap, new Point2D.Double(centerX - spacing, centerY));
         */
        if (stopRequested_.get()) {
            return null;
        }
        try {
            // require that the RMS value between the mapped points and the measured points be less than 5% of image size
            // also require that the RMS value be less than the spacing between points in the galvo/SLM coordinate system
            // (2nd requirement was probably the intent of the code until r15505, but parameters were interchanged in call) 
            final long imageSize = Math.min(core_.getImageWidth(), core_.getImageHeight());
            //return MathFunctions.generateAffineTransformFromPointPairs(spotMap, imageSize * 0.05, spacing);
            return MathFunctions.generateAffineTransformFromPointPairs(big_map);
        } catch (Exception e) {
            throw new RuntimeException("Spots aren't detected as expected. Is DMD in focus and roughly centered in camera's field of view?");
        }
    }

    /**
     * Generate a nonlinear calibration mapping for the current device settings.
     * A rectangular lattice of points is illuminated one-by-one on the
     * projection device, and locations in camera pixels of corresponding spots
     * on the camera image are recorded. For each rectangular cell in the grid,
     * we take the four point mappings (camera to projector) and generate a
     * local AffineTransform using linear least squares. Cells with suspect
     * measured corner positions are discarded. A mapping of cell polygon to
     * AffineTransform is generated.
     */
    private Map<Polygon, AffineTransform> generateNonlinearMapping() {

        // get the affine transform near the center spot
        cur_mode.first_mapping = generateLinearMapping();

        // then use this single transform to estimate what SLM coordinates 
        // correspond to the image's corner positions 
//        final Point2D.Double camCorner1 = (Point2D.Double) firstApproxAffine.transform(new Point2D.Double(0, 0), null);
//        final Point2D.Double camCorner2 = (Point2D.Double) firstApproxAffine.transform(new Point2D.Double((int) core_.getImageWidth(), (int) core_.getImageHeight()), null);
//        final Point2D.Double camCorner3 = (Point2D.Double) firstApproxAffine.transform(new Point2D.Double(0, (int) core_.getImageHeight()), null);
//        final Point2D.Double camCorner4 = (Point2D.Double) firstApproxAffine.transform(new Point2D.Double((int) core_.getImageWidth(), 0), null);

        // figure out camera's bounds in SLM coordinates
        // min/max because we don't know the relative orientation of the camera and SLM
        // do some extra checking in case camera/SLM aren't at exactly 90 degrees from each other, 
        // but still better that they are at 0, 90, 180, or 270 degrees from each other
        // TODO can create grid along camera location instead of SLM's if camera is the limiting factor; this will make arbitrary rotation possible
//        final double camLeft = Math.min(Math.min(Math.min(camCorner1.x, camCorner2.x), camCorner3.x), camCorner4.x);
//        final double camRight = Math.max(Math.max(Math.max(camCorner1.x, camCorner2.x), camCorner3.x), camCorner4.x);
//        final double camTop = Math.min(Math.min(Math.min(camCorner1.y, camCorner2.y), camCorner3.y), camCorner4.y);
//        final double camBottom = Math.max(Math.max(Math.max(camCorner1.y, camCorner2.y), camCorner3.y), camCorner4.y);

        // these are the SLM's bounds
//        final double slmLeft = min_v_x;
//        final double slmRight = v_range_x + min_v_x;
//        final double slmTop = min_v_y;
//        final double slmBottom = v_range_y + min_v_y;

        // figure out the "overlap region" where both the camera and SLM
        // can "see", expressed in SLM coordinates
//        final double left = Math.max(camLeft, slmLeft);
//        final double right = Math.min(camRight, slmRight);
//        final double top = Math.max(camTop, slmTop);
//        final double bottom = Math.min(camBottom, slmBottom);
//        final double width = right - left;
//        final double height = bottom - top;



        // compute a grid of SLM points inside the "overlap region"
        // nGrid is how many polygons in both X and Y
        // require (nGrid + 1)^2 spot measurements to get nGrid^2 squares
        // TODO allow user to change nGrid
        final int nGrid = 7;
        Point2D.Double slmPoint[][] = new Point2D.Double[1 + nGrid][1 + nGrid];
        Point2D.Double camPoint[][] = new Point2D.Double[1 + nGrid][1 + nGrid];

        final int padding = 10;
        final int cam_width = (int) core_.getImageWidth() - padding * 2;
        final int cam_height = (int) core_.getImageHeight() - padding * 2;
        final double cam_step_x = cam_width / nGrid;
        final double cam_step_y = cam_height / nGrid;

        for (int i = 0; i <= nGrid; i++) {
            for (int j = 0; j <= nGrid; j++) {
                slmPoint[i][j] = (Point2D.Double) cur_mode.first_mapping.transform(
                        new Point2D.Double(cam_step_x * i + padding,
                        cam_step_y * j + padding), null);
            }
        }
        // tabulate the camera spot at each of SLM grid points
        for (int i = 0; i <= nGrid; ++i) {
            for (int j = 0; j <= nGrid; ++j) {
                Point spot = measureSpotOnCamera(slmPoint[i][j], true);
                if (spot != null) {
                    camPoint[i][j] = toDoublePoint(spot);
                }
            }
        }

        if (stopRequested_.get()) {
            return null;
        }

        // now make a grid of (square) polygons (in camera's coordinate system)
        // and generate an affine transform for each of these square regions
        Map<Polygon, AffineTransform> bigMap = new HashMap<Polygon, AffineTransform>();
        for (int i = 0; i <= nGrid - 1; ++i) {
            for (int j = 0; j <= nGrid - 1; ++j) {
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

    private Preferences getCalibrationNode() {
        try {
            Preferences p = Preferences.userNodeForPackage(DualAxisMirrorPlugin.class)
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

    private void run_external_program(String prog_name, List<String> args, boolean will_done) {
        try {
            String app = System.getProperty("user.dir")
                    + File.separator + "mmplugins" + File.separator
                    + prog_name;

            args.add(0, app);

            ProcessBuilder pb = new ProcessBuilder(args);
            daq_proc = pb.start();
            
            if (will_done) {
                daq_proc.waitFor();
                daq_proc.destroy();
            }
        } catch (IOException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void saveToDisk(ExpMode mode) {
        Preferences p = getCalibrationNode();
        JavaUtils.putObjectInPrefs(p, mode.mode_name, mode);
    }

    public void stopCalibration() {
        stopFreerun();
        stopRequested_.set(true);
    }

    public void runCalibration() {
        final boolean liveModeRunning = app_.isLiveModeOn();
        app_.enableLiveMode(false);
        if (!isRunning_.get()) {
            stopRequested_.set(false);

            cur_mode = mode_map.get(light_mode_drop.getSelectedItem().toString());

            if (camera_name_ui.getItemCount() == 0) {
                JOptionPane.showMessageDialog(null,
                        "No camera found. Require a camera to detect signals.");
                return;
            }
            if (x_axis_ui.getText().isEmpty()) {
                JOptionPane.showMessageDialog(null,
                        "Need to specify x-axis signal");
                return;
            }
            if (y_axis_ui.getText().isEmpty()) {
                JOptionPane.showMessageDialog(null,
                        "Need to specify y-axis signal");
                return;
            }
            if ((Double)um_per_pix_ui.getValue() == 0) {
                JOptionPane.showMessageDialog(null,
                        "Need to specify pixel size");
                return;
            }
            
            // populate properties in cur_mode from GUI
            cur_mode.mode_name = light_mode_drop.getSelectedItem().toString();
            cur_mode.camera_name = camera_name_ui.getSelectedItem().toString();
            cur_mode.daq_dev_str = x_axis_ui.getText() + "," + y_axis_ui.getText();
            cur_mode.um_per_pix  = (Double)um_per_pix_ui.getValue();
            
            Point2D.Double zero_v = new Point2D.Double(0, 0);
            Point zero_p = measureSpotOnCamera(zero_v, false);
            cur_mode.center_x = zero_p.x;
            cur_mode.center_y = zero_p.y;
            
            Thread th = new Thread("Projector calibration thread") {
                @Override
                public void run() {
                    try {
                        isRunning_.set(true);
                        //Roi originalROI = IJ.getImage().getRoi();
                        cur_mode.poly_mapping =
                                (HashMap<Polygon, AffineTransform>) generateNonlinearMapping();

                        if (!stopRequested_.get()) {
                            saveToDisk(cur_mode);
                        }
                        
                        app_.enableLiveMode(liveModeRunning);
                        JOptionPane.showMessageDialog(IJ.getImage().getWindow(), "Calibration "
                                + (!stopRequested_.get() ? "finished." : "canceled."));
                        //IJ.getImage().setRoi(originalROI);

                    } catch (HeadlessException e) {
                        ReportingUtils.showError(e);
                    } catch (RuntimeException e) {
                        ReportingUtils.showError(e);
                    } finally {
                        if (stopRequested_.get() == false) {
                            mark_calibration_label(cur_mode.mode_name);
                            if (cur_mode.mode_name.equals(mode_str_array.get(0))) {
                                original_center_x_ui.setText(Integer.toString(cur_mode.center_x));
                                original_center_y_ui.setText(Integer.toString(cur_mode.center_y));
                                center_input_x_ui.setValue(Integer.valueOf(cur_mode.center_x));
                                center_input_y_ui.setValue(Integer.valueOf(cur_mode.center_y));
                            }
                        }
                        isRunning_.set(false);
                        stopRequested_.set(false);
                        calibration_button.setText("Calibrate Now!");

                    }
                }
            };
            th.start();            
        }
    }

    private void calibration_buttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_calibration_buttonActionPerformed

        try {
            boolean running = isRunning_.get();
            if (running) {
                stopCalibration();
                calibration_button.setText("Calibrate Now!");
            } else {
                
                runCalibration();
                calibration_button.setText("Stop Calibration");
            }
        } catch (Exception e) {
            ReportingUtils.showError(e);
        }




    }//GEN-LAST:event_calibration_buttonActionPerformed

    /**
     * Load the mapping for the current calibration node. The mapping maps each
     * polygon cell to an AffineTransform.
     */
    public void two_ao_update(String channel_str, String xv, String yv) {
        try {

            ProcessBuilder pb = new ProcessBuilder(System.getProperty("user.dir")
                    + File.separator + "mmplugins" + File.separator
                    + "two_ao_update.exe", channel_str, xv, yv);//.redirectErrorStream(true);
            daq_proc = pb.start();

            daq_proc.getInputStream().close();
            daq_proc.getOutputStream().close();
            daq_proc.getErrorStream().close();

            daq_proc.waitFor();
            daq_proc.destroy();
        } catch (IOException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

//    // Creates a MouseListener instance for future use with Point and Shoot
//   // mode. When the MouseListener is attached to an ImageJ window, any
//   // clicks will result in a spot being illuminated.
//   private MouseListener createPointAndShootMouseListenerInstance() {
//      return new MouseAdapter() {
//         @Override
//         public void mouseClicked(MouseEvent e) {
//            if (e.isShiftDown()) {
//               Point p = e.getPoint();
//               ImageCanvas canvas = (ImageCanvas) e.getSource();
//               Point pOffscreen = new Point(canvas.offScreenX(p.x), canvas.offScreenY(p.y));
//               final Point2D.Double devP = transformAndMirrorPoint(loadMapping(), canvas.getImage(),
//                       new Point2D.Double(pOffscreen.x, pOffscreen.y));
//               final Configuration originalConfig = prepareChannel();
//               final boolean originalShutterState = prepareShutter();
//               makeRunnableAsync(
//                       new Runnable() {
//                          @Override
//                          public void run() {
//                             try {
//                                if (devP != null) {
//                                   displaySpot(devP.x, devP.y);
//                                }
//                                returnShutter(originalShutterState);
//                                returnChannel(originalConfig);
//                             } catch (Exception e) {
//                                ReportingUtils.showError(e);
//                             }
//                          }
//                       }).run();
//
//            }
//         }
//      };
//   }
    private void point_shoot_buttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_point_shoot_buttonActionPerformed

        double x = Double.parseDouble(point_shoot_x.getText());
        double y = Double.parseDouble(point_shoot_y.getText());

        Point2D.Double img_pos = new Point2D.Double(x, y);

        Point2D.Double volts = transformPoint(cur_mode.poly_mapping, img_pos);

        // TODO REMOVE THIS LINE!!!!!
        cur_mode = mode_map.get(mode_str_array.get(0));

        displaySpot(volts.x, volts.y);
    }//GEN-LAST:event_point_shoot_buttonActionPerformed

    private void update_cur_mode_based_on_tab() {
        int tab_ind = tabbed_panel.getSelectedIndex();
        if (tab_ind < 2) {
            cur_mode = mode_map.get(mode_str_array.get(tab_ind));
        }
    }
    private void tabbed_panelStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_tabbed_panelStateChanged
        update_cur_mode_based_on_tab();
//        mapping_ = mapping_map.get(cur_mode);
        //first_mapping_ = first_mapping_map.get(tabbed_panel.getSelectedIndex());
        //mapping_ = mapping_map.get(tabbed_panel.getSelectedIndex());
    }//GEN-LAST:event_tabbed_panelStateChanged

    private boolean checkCalibration() {
        if (cur_mode.daq_dev_str == null) {
            JOptionPane.showMessageDialog(null, "No Calibration Data");
            return false;
        }
        return true;
    }
    
    private List<Double> create_circle_dots(int center_x, int center_y) {
        if (cur_mode == null) {
            JOptionPane.showMessageDialog(null,
                        "No calibration found during creating circles.");
            return null;
        }
        List<Double> ret = new ArrayList<Double>();

        int num_dots = (Integer)circle_samples_ui.getValue();
        double radius = ((Integer)circle_radius_ui.getValue()).doubleValue() /
                     cur_mode.um_per_pix;

        double unit_angle = (360.0 / num_dots) * (Math.PI / 180.0);

        for (int i = 0; i < num_dots; i++) {
            double dot_x = Math.cos(unit_angle * i) * radius + center_x;
            double dot_y = Math.sin(unit_angle * i) * radius + center_y;

            ret.add(dot_x);
            ret.add(dot_y);

        }
        //System.out.print(ret.toString());
        return ret;
    }

    public void freerun(List<String> args) {
        try {
            String app = System.getProperty("user.dir")
                    + File.separator + "mmplugins" + File.separator
                    + "freerun.exe";

            args.add(0, app);

            ProcessBuilder pb = new ProcessBuilder(args);
            daq_proc = pb.start();

        } catch (IOException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void reset_daq(String dev_str) {
        try {
            String app = System.getProperty("user.dir")
                    + File.separator + "mmplugins" + File.separator
                    + "reset_daq.exe";

            ProcessBuilder pb = new ProcessBuilder(app, dev_str);
            daq_proc = pb.start();
            daq_proc.waitFor();
            daq_proc.destroy();
            
        } catch (IOException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    private void startFreerun() {
        /*
         if (mapping_ == null)
         return;
         */
        
        //Point2D.Double zero_v = new Point2D.Double(0, 0);
        //Point zero_p = measureSpotOnCamera(zero_v, false);
        
        //zero_v_x = zero_p.x;
        //zero_v_y = zero_p.y;
        
        final List<Double> combined = create_circle_dots((Integer)center_input_x_ui.getValue(),
                                                         (Integer)center_input_y_ui.getValue());
        /*
         final List<Double> combined = new ArrayList<Double>();
         combined.add(400.0);
         combined.add(400.0);
         combined.add(500.0);
         combined.add(400.0);
         combined.add(500.0);
         combined.add(500.0);
         combined.add(400.0);
         combined.add(500.0);
         */
        final List<String> transformed_points = new ArrayList<String>();


        for (int i = 0; i < combined.size() - 1; i += 2) {
            Point2D.Double p = new Point2D.Double(combined.get(i), combined.get(i + 1));
            Point2D.Double trans_p = transformPoint(cur_mode.poly_mapping, p);

            transformed_points.add(String.valueOf(trans_p.x));
            transformed_points.add(String.valueOf(trans_p.y));
        }

        int sampling_rate = (int) ((Integer)circle_samples_ui.getValue() *
                                   (Double)circle_frequency_ui.getValue());
                            
        
        // example: run dev1/ao0,dev1/ao1 rate 6 x1 y1 x2 y2 x3 y3
        List<String> args = Arrays.asList(cur_mode.daq_dev_str,
                Integer.toString(sampling_rate),
                Integer.toString(transformed_points.size()));

        transformed_points.addAll(0, args);

        final boolean liveModeRunning = app_.isLiveModeOn();
        if (!isRunning_.get()) {
            stopRequested_.set(false);
            Thread th = new Thread("Projector calibration thread") {
                @Override
                public void run() {
                    try {
                        isRunning_.set(true);
                        freerun(transformed_points);

                        app_.enableLiveMode(liveModeRunning);
                    } catch (HeadlessException e) {
                        ReportingUtils.showError(e);
                    } catch (RuntimeException e) {
                        ReportingUtils.showError(e);
                    } finally {
                        //isRunning_.set(false);
                        //stopRequested_.set(false);
                        //calibration_button.setText("Calibrate");     
                    }
                }
            };
            th.start();
        }
    }

    private void stopFreerun() {
        daq_proc.destroy();
        isRunning_.set(false);
    }

    private void freerun_buttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_freerun_buttonActionPerformed

        if (!checkCalibration())
            return;
        
        boolean running = isRunning_.get();
        if (running) {
            stopFreerun();
            freerun_button.setText("Free Run");
        } else {
            //app_.enableLiveMode(false);
            startFreerun();
            //app_.enableLiveMode(true);
            freerun_button.setText("Cancel");
        }
    }//GEN-LAST:event_freerun_buttonActionPerformed

    private void reset_daq_signal(String dev, double x, double y) {
        
    }
            
    private void reset_daq_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_reset_daq_uiActionPerformed

        // Get the device name from current mode
        String dev_name = dev_name_ui.getText();
        reset_daq(dev_name);
        
        /*
        // TODO Find a way to loop through all modes without hard-coded index
        Point2D.Double zero_v = new Point2D.Double(0, 0);
        //cur_mode = mode_map.get(mode_str_array.get(1));
        //displaySpot(0, 0);
        //cur_mode = mode_map.get(mode_str_array.get(0));
        final boolean liveModeRunning = app_.isLiveModeOn();
        app_.enableLiveMode(false);
        Point p = measureSpotOnCamera(zero_v, false);
        
        zero_v_x = p.x;
        zero_v_y = p.y;
        
        app_.enableLiveMode(liveModeRunning);
        */
    }//GEN-LAST:event_reset_daq_uiActionPerformed

    private void add_circle_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_add_circle_uiActionPerformed
        TIRFCircle tc = new TIRFCircle();
        
        List<Double> circle_px = create_circle_dots((Integer)center_input_x_ui.getValue(),
                                                    (Integer)center_input_y_ui.getValue());
      
        if (circle_px == null)
            return;
        
        List<String> transformed_points = new ArrayList<String>();
        for (int i = 0; i < circle_px.size() - 1; i += 2) {
            Point2D.Double p = new Point2D.Double(circle_px.get(i), circle_px.get(i + 1));
            Point2D.Double trans_p = transformPoint(cur_mode.poly_mapping, p);

            transformed_points.add(String.valueOf(trans_p.x));
            transformed_points.add(String.valueOf(trans_p.y));
        }
        
        tc.volts = transformed_points;
        tc.radius_um = (Integer)circle_radius_ui.getValue();
        tc.circle_frequency = (Double)circle_frequency_ui.getValue();
        tc.center_x = (Integer)center_input_x_ui.getValue();
        tc.center_y = (Integer)center_input_y_ui.getValue();
        
        tirf_loops.add(tc);
        tirf_loops_model.addElement(tc.toString());
        update_tirf_model_ui();
    }//GEN-LAST:event_add_circle_uiActionPerformed

    private void stop_submit_circles() {
        daq_proc.destroy();
        isRunning_.set(false);
    }
    
    private void submit_circles() {
        // prepare arguments before calling an external program
        final List<String> args = new ArrayList<String>();
        
        args.add(cur_mode.daq_dev_str);
        args.add("/Dev1/PFI0");
        args.add("2");
        args.add(Double.toString(tirf_loops.get(0).circle_frequency * 
                 tirf_loops.get(0).volts.size()/2));
        args.add(Integer.toString(tirf_loops.size()));
        args.add(Integer.toString(tirf_loops.size()/2));
        
        for (TIRFCircle tc: tirf_loops) {
            args.addAll(tc.volts);
        }
        
        final boolean liveModeRunning = app_.isLiveModeOn();
        if (!isRunning_.get()) {
            stopRequested_.set(false);
            Thread th = new Thread("Projector calibration thread") {
                @Override
                public void run() {
                    try {
                        isRunning_.set(true);
                        run_external_program("ao_patterns_triggered.exe",
                                             args, false);

                        app_.enableLiveMode(liveModeRunning);
                    } catch (HeadlessException e) {
                        ReportingUtils.showError(e);
                    } catch (RuntimeException e) {
                        ReportingUtils.showError(e);
                    } finally {
                        //isRunning_.set(false);
                        //stopRequested_.set(false);
                        //calibration_button.setText("Calibrate");     
                    }
                }
            };
            th.start();
        }
    }
    private void submit_circles_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_submit_circles_uiActionPerformed
        /*
        if (!checkCalibration())
            return;
        */
        
        boolean running = isRunning_.get();
        if (running) {
            stop_submit_circles();
            submit_circles_ui.setText("Submit Circles");
        } else {
            //app_.enableLiveMode(false);
            if (tirf_loops.size() <= 0) {
                JOptionPane.showMessageDialog(null, "No circles in loop");
                return;
            }
            submit_circles();
            //app_.enableLiveMode(true);
            submit_circles_ui.setText("Cancel");
        }
        
        
    }//GEN-LAST:event_submit_circles_uiActionPerformed
    /**
     * @param args the command line arguments
     */
//    public static void main(String args[]) {
//        /* Set the Nimbus look and feel */
//        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
//        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
//         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
//         */
//        try {
//            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
//                if ("Nimbus".equals(info.getName())) {
//                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
//                    break;
//                }
//            }
//        } catch (ClassNotFoundException ex) {
//            java.util.logging.Logger.getLogger(MirrorControlForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
//        } catch (InstantiationException ex) {
//            java.util.logging.Logger.getLogger(MirrorControlForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
//        } catch (IllegalAccessException ex) {
//            java.util.logging.Logger.getLogger(MirrorControlForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
//        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
//            java.util.logging.Logger.getLogger(MirrorControlForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
//        }
//        //</editor-fold>
//
//        /* Create and display the form */
//        java.awt.EventQueue.invokeLater(new Runnable() {
//            public void run() {
//                new MirrorControlForm().setVisible(true);
//            }
//        });
//    }                    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton add_circle_ui;
    private javax.swing.JButton calibration_button;
    private javax.swing.JComboBox camera_name_ui;
    private javax.swing.JSpinner center_input_x_ui;
    private javax.swing.JSpinner center_input_y_ui;
    private javax.swing.JSpinner circle_frequency_ui;
    private javax.swing.JSpinner circle_radius_ui;
    private javax.swing.JSpinner circle_samples_ui;
    private javax.swing.JTextField dev_name_ui;
    private javax.swing.JButton freerun_button;
    private javax.swing.JSpinner input_volt_x_ui;
    private javax.swing.JSpinner input_volt_y_ui;
    private javax.swing.JButton jButton10;
    private javax.swing.JButton jButton11;
    private javax.swing.JButton jButton12;
    private javax.swing.JButton jButton2;
    private javax.swing.JButton jButton3;
    private javax.swing.JButton jButton4;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel21;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel24;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel26;
    private javax.swing.JLabel jLabel27;
    private javax.swing.JLabel jLabel28;
    private javax.swing.JLabel jLabel29;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel31;
    private javax.swing.JLabel jLabel32;
    private javax.swing.JLabel jLabel33;
    private javax.swing.JLabel jLabel34;
    private javax.swing.JLabel jLabel35;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSpinner jSpinner1;
    private javax.swing.JSpinner jSpinner2;
    private javax.swing.JSpinner jSpinner3;
    private javax.swing.JTable jTable1;
    private javax.swing.JComboBox light_mode_drop;
    private javax.swing.JLabel original_center_x_ui;
    private javax.swing.JLabel original_center_y_ui;
    private javax.swing.JLabel photobleaching_calibration_sign;
    private javax.swing.JButton point_shoot_button;
    private javax.swing.JTextField point_shoot_x;
    private javax.swing.JTextField point_shoot_y;
    private javax.swing.JButton reset_daq_ui;
    private javax.swing.JButton submit_circles_ui;
    private javax.swing.JTabbedPane tabbed_panel;
    private javax.swing.JLabel tirf_calibration_sign;
    private javax.swing.JList tirf_loops_ui;
    private javax.swing.JSpinner um_per_pix_ui;
    private javax.swing.JTextField x_axis_ui;
    private javax.swing.JTextField y_axis_ui;
    // End of variables declaration//GEN-END:variables
}
