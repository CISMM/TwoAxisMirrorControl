package cismm;

import ij.ImagePlus;
import ij.io.FileSaver;
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
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
//import java.util.prefs.Preferences;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import static javax.swing.WindowConstants.HIDE_ON_CLOSE;
import mmcorej.CMMCore;
import mmcorej.DeviceType;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.ImageUtils;
import org.micromanager.utils.JavaUtils;
import org.micromanager.utils.ReportingUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import javax.swing.JTextField;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author phsiao
 */
public class MirrorControlForm extends javax.swing.JFrame {

    static final String version_str = "1.1";
    
    //TIRFPanel tirf_pan = new TIRFPanel();
    
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
    
    public class CircleListModel extends DefaultListModel {

        List<TIRFCircle> circle_list = new ArrayList<TIRFCircle>();

        
//        @Override
//        public Object getElementAt(int arg0) {
//            return circle_list.get(arg0);
//        }
//
//        @Override
//        public int getSize() {
//            return circle_list.size();
//        }

        public void addElement(TIRFCircle t) {
            System.out.println("adding");
            circle_list.add(t);
            System.out.println(circle_list.toString());
            fireIntervalAdded(this, getSize()-1, getSize()-1);
        }

        public void removeElement(int ind) {
            circle_list.remove(ind);
            fireIntervalRemoved(this, ind, ind);
        }
    }
    /**
     * Creates new form MirrorControlForm
     */
    private ScriptInterface app_;
    private CMMCore core_;
    
    protected static AtomicBoolean is_daq_running = new AtomicBoolean(false);
    
       
    static private List<String> daq_bin_list;
    
    //private Thread calibrate_thread = null;
    public static Process daq_proc = null;
    public static ExpMode cur_mode = null;
    
    
    Map<String, ExpMode> mode_map = new HashMap<String, ExpMode>();
    
    
    //List<TIRFCircle> tirf_loops = new ArrayList<TIRFCircle>();
    // Model of the JList that contains the submitted circles.
    DefaultListModel tirf_loops_model = new DefaultListModel();
    
    BufferedWriter writer_to_daq = null;

    
    // The order of the strings must match the order of the tabs on the GUI. 
    List<String> mode_str_array = Arrays.asList("TIRF", "FRAP");
    
    public static void stop_daq_proc() {
       if (daq_proc != null) {
            daq_proc.destroy();
            try {
                daq_proc.waitFor();
            } catch (InterruptedException ex) {
                Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
            }
        }     
    }
    
    public void cleanup() {
        if (daq_proc != null) {
            daq_proc.destroy();
            try {
                daq_proc.waitFor();
            } catch (InterruptedException ex) {
                Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    private void fill_camera_list() {
        StrVector devices = core_.getLoadedDevicesOfType(DeviceType.CameraDevice);
        camera_name_ui.setModel(new DefaultComboBoxModel(devices.toArray()));
        camera_name_ui1.setModel(new DefaultComboBoxModel(devices.toArray()));
    }

    private void mark_calibration_label(String calibration_name) {
        if (calibration_name.equals(mode_str_array.get(0))) {
                    tirf_calibration_sign.setText("FOUND");
                    tirf_calibration_sign.setForeground(Color.green);
                    tirf_calibration_sign.setBackground(Color.black);
                    tirf_calibration_sign.setOpaque(true);
                } else if (calibration_name.equals(mode_str_array.get(1))) {
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
    
    /*
     * This section has functions that are meant to be called from exteranl
     * programs.
     * return type: double[x, y, isGoood]
     */
    public double[] point_to(int x, int y, String mode) {
        
        System.out.println(mode);
        ExpMode m = mode_map.get(mode);
        System.out.println(mode);
        if (m==null) return new double[]{0, 0, 0};
        
        /*
        ExpMode m = get_calibration(mode);
        if (m == null)
            return false;
        */
        //Point2D.Double transformPoint(Map<Polygon, AffineTransform> mapping, Point2D.Double pt)
        Point2D.Double volt = Util.transformPoint(m.poly_mapping, new Point2D.Double(x, y));
        Util.set_voltage(m.daq_dev_str, volt.x, volt.y);
        return new double[]{volt.x, volt.y, 1};
    }
    
    public ExpMode get_calibration(String mode_str) {
        Preferences prefs = getCalibrationNode();
        if (prefs == null) {
            return null;
        }
        ExpMode m = (ExpMode) JavaUtils.getObjectFromPrefs(prefs, mode_str, new ExpMode());
            
        if (m.first_mapping == null) {
            return null;
        }
        return m;
    }
    
    // ----------------------------------------------------------------------
    static public void create_daq_bins()
    {   
        daq_bin_list = Arrays.asList("two_ao_update.exe",
                                      "daq_trigger.exe",
                                      "freerun.exe",
                                      "reset_daq.exe",
                                      "ao_patterns_triggered.exe");
        InputStream is = null;
        OutputStream os = null;
        
        for(String bin_file: daq_bin_list)
        {
            try {
                is = MirrorControlForm.class.getResource("/cismm/NI_daq_bin/" + bin_file).openStream();
                os = new FileOutputStream(Util.jar_path()+ File.separator + bin_file);
                //2048 here is just my preference
                byte[] b = new byte[2048];
                int length;
                while ((length = is.read(b)) != -1) {
                    os.write(b, 0, length);
                }
            } catch (IOException ex) {
                Logger.getLogger(DualAxisMirrorPlugin.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException ex) {
                        Logger.getLogger(DualAxisMirrorPlugin.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException ex) {
                        Logger.getLogger(DualAxisMirrorPlugin.class.getName()).log(Level.SEVERE, null, ex);
                    }
                } 
            }
        }
    }
    static public void del_daq_bins() {
        for (String file: daq_bin_list)
        {
            File f = new File(Util.jar_path()+ File.separator + file);
            f.delete();
        }     
    }
    public MirrorControlForm(CMMCore core, ScriptInterface app) {
        initComponents();
        core_ = core;
        app_ = app;
        this.setDefaultCloseOperation(HIDE_ON_CLOSE);
        this.setTitle("DualAxisMirror Plugin - " + version_str);
        
        
        light_mode_drop.setModel(new DefaultComboBoxModel(mode_str_array.toArray()));
        tirf_loops_ui.setModel(tirf_loops_model);
        
        /*
        try{
            PrintWriter writer = new PrintWriter("C:\\Users\\phsiao\\Desktop\\the-file-name.txt", "UTF-8");
            writer.println(Util.jar_path());
            writer.close();
        } catch (IOException e) {
           // do something
        }
        */
        fill_mode_map();
        fill_camera_list();      
        create_daq_bins();
        update_cur_mode_based_on_tab();
        
        //tabbed_panel.setEnabledAt(1, false); 
        
        
        //System.out.println(ClassLoader.getSystemClassLoader().getResource(".").getPath());
        
        //URL pa = MirrorControlForm.class.getResource("NI_daq_bin");
            
        //System.out.println(pa.toString().substring(1)+File.separator);
        //System.out.println(ClassLoader.getSystemClassLoader().getResource(".").getPath());
                
        //URL exe = MirrorControlForm.class.getResource("NI_daq_bin/");
        //System.out.println(exe.getPath());
        //System.out.println(exe.getPath());
        //File exefile = new File(exe.getPath());
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
        jLabel22 = new javax.swing.JLabel();
        jLabel18 = new javax.swing.JLabel();
        original_center_x_ui = new javax.swing.JLabel();
        jLabel29 = new javax.swing.JLabel();
        original_center_y_ui = new javax.swing.JLabel();
        jLabel31 = new javax.swing.JLabel();
        center_input_x_ui = new javax.swing.JSpinner();
        jLabel32 = new javax.swing.JLabel();
        center_input_y_ui = new javax.swing.JSpinner();
        freerun_ui = new javax.swing.JToggleButton();
        jPanel5 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        tirf_loops_ui = new javax.swing.JList();
        remove_circle_ui = new javax.swing.JButton();
        move_circle_up_ui = new javax.swing.JButton();
        move_circle_down_ui = new javax.swing.JButton();
        add_circle_range_gui = new javax.swing.JButton();
        clear_circle_ui = new javax.swing.JButton();
        add_circle_ui = new javax.swing.JButton();
        input_volt_x_ui = new javax.swing.JSpinner();
        input_volt_y_ui = new javax.swing.JSpinner();
        save_circle_maps_ui = new javax.swing.JButton();
        camera_name_ui1 = new javax.swing.JComboBox();
        submit_circles_ui = new javax.swing.JToggleButton();
        input_volt_set_ui = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        point_shoot_button = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        jSpinner1 = new javax.swing.JSpinner();
        jLabel7 = new javax.swing.JLabel();
        roi_manager_ui = new javax.swing.JButton();
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
        jPanel6 = new javax.swing.JPanel();
        jLabel36 = new javax.swing.JLabel();
        jScrollPane3 = new javax.swing.JScrollPane();
        pa_table = new javax.swing.JTable();
        add_pa_row_ui = new javax.swing.JButton();
        remove_pa_row_ui = new javax.swing.JButton();
        jSeparator3 = new javax.swing.JSeparator();
        jLabel37 = new javax.swing.JLabel();
        jLabel38 = new javax.swing.JLabel();
        jSpinner5 = new javax.swing.JSpinner();
        jScrollPane4 = new javax.swing.JScrollPane();
        property_table = new javax.swing.JTable();
        start_pa_ui = new javax.swing.JButton();
        add_property_ui = new javax.swing.JButton();
        remove_property_ui = new javax.swing.JButton();
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
        reset_daq_ui = new javax.swing.JButton();
        jLabel27 = new javax.swing.JLabel();
        dev_name_ui = new javax.swing.JTextField();
        calibrate_ui = new javax.swing.JToggleButton();
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

        circle_radius_ui.setModel(new javax.swing.SpinnerNumberModel(Integer.valueOf(4200), Integer.valueOf(0), null, Integer.valueOf(1)));

        circle_samples_ui.setModel(new javax.swing.SpinnerNumberModel(Integer.valueOf(200), Integer.valueOf(0), null, Integer.valueOf(1)));

        circle_frequency_ui.setModel(new javax.swing.SpinnerNumberModel(Double.valueOf(30.0d), Double.valueOf(0.1d), null, Double.valueOf(1.0d)));

        jLabel22.setText("Hz");

        jLabel18.setText("Detected Center:");

        original_center_x_ui.setText("-1");

        jLabel29.setText(",");

        original_center_y_ui.setText("-1");

        jLabel31.setText("CenterX:");

        center_input_x_ui.setModel(new javax.swing.SpinnerNumberModel());

        jLabel32.setText("CenterY:");

        center_input_y_ui.setModel(new javax.swing.SpinnerNumberModel());

        freerun_ui.setText("Free Run");
        freerun_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                freerun_uiActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
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
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel4Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jLabel21)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(circle_frequency_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel22)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(freerun_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)))
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
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
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
                .addGap(2, 2, 2)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel21)
                    .addComponent(circle_frequency_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel22)
                    .addComponent(freerun_ui))
                .addGap(27, 27, 27))
        );

        jPanel5.setBorder(javax.swing.BorderFactory.createTitledBorder("Circle Loops (radius in um)"));

        tirf_loops_ui.setModel(new javax.swing.AbstractListModel() {
            String[] strings = { "Item 1", "Item 2", "Item 3" };
            public int getSize() { return strings.length; }
            public Object getElementAt(int i) { return strings[i]; }
        });
        jScrollPane1.setViewportView(tirf_loops_ui);

        remove_circle_ui.setText("Remove");
        remove_circle_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                remove_circle_uiActionPerformed(evt);
            }
        });

        move_circle_up_ui.setText("Move Up");
        move_circle_up_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                move_circle_up_uiActionPerformed(evt);
            }
        });

        move_circle_down_ui.setText("Move Down");
        move_circle_down_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                move_circle_down_uiActionPerformed(evt);
            }
        });

        add_circle_range_gui.setText("Add Circles...");
        add_circle_range_gui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                add_circle_range_guiActionPerformed(evt);
            }
        });

        clear_circle_ui.setText("Clear");
        clear_circle_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clear_circle_uiActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                    .addComponent(add_circle_range_gui, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, 110, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(remove_circle_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(move_circle_up_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(move_circle_down_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(clear_circle_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(6, 6, 6))
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addComponent(remove_circle_ui)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(move_circle_up_ui)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(move_circle_down_ui)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(clear_circle_ui)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(add_circle_range_gui)
                .addGap(37, 37, 37))
        );

        add_circle_ui.setText("Add Circle to Loop");
        add_circle_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                add_circle_uiActionPerformed(evt);
            }
        });

        input_volt_x_ui.setModel(new javax.swing.SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.01d));

        input_volt_y_ui.setModel(new javax.swing.SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.01d));

        save_circle_maps_ui.setText("Save circle maps");
        save_circle_maps_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                save_circle_maps_uiActionPerformed(evt);
            }
        });

        submit_circles_ui.setText("Submit Circles");
        submit_circles_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                submit_circles_uiActionPerformed(evt);
            }
        });

        input_volt_set_ui.setText("Set");
        input_volt_set_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                input_volt_set_uiActionPerformed(evt);
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
                        .addComponent(jLabel17)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(input_volt_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel19)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(input_volt_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel24)
                        .addGap(162, 162, 162)
                        .addComponent(submit_circles_ui)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(input_volt_set_ui)
                        .addGap(225, 225, 225)
                        .addComponent(camera_name_ui1, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(save_circle_maps_ui)
                        .addContainerGap())
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(add_circle_ui, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, 259, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(9, 9, 9)
                        .addComponent(jPanel5, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(13, 13, 13))))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addContainerGap(15, Short.MAX_VALUE)
                        .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, 149, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(add_circle_ui)
                        .addGap(9, 9, 9))
                    .addComponent(jPanel5, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addGap(18, 18, 18)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel19)
                            .addComponent(jLabel24)
                            .addComponent(jLabel17)
                            .addComponent(input_volt_x_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(input_volt_y_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addComponent(submit_circles_ui)
                        .addGap(15, 15, 15)))
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(input_volt_set_ui)
                        .addGap(38, 38, 38))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(save_circle_maps_ui)
                            .addComponent(camera_name_ui1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(19, 19, 19))))
        );

        tabbed_panel.addTab("TIRF", jPanel1);

        point_shoot_button.setText("Point and shoot");

        jLabel1.setText("Turn off light after:");

        jLabel2.setText("Current target:");

        jLabel3.setText("512");

        jLabel5.setText("320");

        jLabel6.setText("(To phototarget, Ctrl + click on the image)");

        jLabel7.setText("ms");

        roi_manager_ui.setText("ROI Manager >>");
        roi_manager_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                roi_manager_uiActionPerformed(evt);
            }
        });

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
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED, 53, Short.MAX_VALUE)))
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
                            .addComponent(roi_manager_ui)
                            .addComponent(jLabel8))
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jButton4)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel11)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jSpinner3, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jLabel14)
                        .addContainerGap(65, Short.MAX_VALUE))))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(roi_manager_ui)
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
                .addGap(0, 101, Short.MAX_VALUE)
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 183, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(35, 35, 35))
        );

        tabbed_panel.addTab("FRAP", jPanel3);

        jLabel36.setText("Photoactivation timing");

        pa_table.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "start frame", "end frame", "x position", "y position"
            }
        ));
        jScrollPane3.setViewportView(pa_table);

        add_pa_row_ui.setText("Add");
        add_pa_row_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                add_pa_row_uiActionPerformed(evt);
            }
        });

        remove_pa_row_ui.setText("Remove");
        remove_pa_row_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                remove_pa_row_uiActionPerformed(evt);
            }
        });

        jLabel37.setText("Take extra ");

        jLabel38.setText("frames");

        property_table.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Property", "when plain image", "when photoactivation"
            }
        ));
        jScrollPane4.setViewportView(property_table);

        start_pa_ui.setText("Start now");
        start_pa_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                start_pa_uiActionPerformed(evt);
            }
        });

        add_property_ui.setText("Add");
        add_property_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                add_property_uiActionPerformed(evt);
            }
        });

        remove_property_ui.setText("Remove");

        javax.swing.GroupLayout jPanel6Layout = new javax.swing.GroupLayout(jPanel6);
        jPanel6.setLayout(jPanel6Layout);
        jPanel6Layout.setHorizontalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator3)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 399, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(add_pa_row_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(remove_pa_row_ui, javax.swing.GroupLayout.DEFAULT_SIZE, 85, Short.MAX_VALUE)))
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel36)
                            .addGroup(jPanel6Layout.createSequentialGroup()
                                .addComponent(jLabel37)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jSpinner5, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel38)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jScrollPane4, javax.swing.GroupLayout.PREFERRED_SIZE, 401, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(add_property_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(remove_property_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addComponent(start_pa_ui, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel6Layout.setVerticalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel36)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(add_pa_row_ui)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(remove_pa_row_ui)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel37)
                    .addComponent(jSpinner5, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel38))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSeparator3, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane4, javax.swing.GroupLayout.PREFERRED_SIZE, 72, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(add_property_ui)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(remove_property_ui)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(start_pa_ui)
                .addContainerGap(51, Short.MAX_VALUE))
        );

        tabbed_panel.addTab("PhotoActivation", jPanel6);

        jPanel7.setBorder(javax.swing.BorderFactory.createTitledBorder("Setup"));

        jLabel15.setText("Camera:");

        jLabel33.setText("X Axis:");

        jLabel34.setText("Y Axis:");

        x_axis_ui.setText("Dev1/ao2");

        y_axis_ui.setText("Dev1/ao3");

        jLabel23.setText("Pixel Size:");

        um_per_pix_ui.setModel(new javax.swing.SpinnerNumberModel(Double.valueOf(5.5d), Double.valueOf(0.0d), null, Double.valueOf(0.1d)));

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

        reset_daq_ui.setText("Reset DAQ");
        reset_daq_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                reset_daq_uiActionPerformed(evt);
            }
        });

        jLabel27.setText("on");

        dev_name_ui.setText("Dev1");

        calibrate_ui.setText("Calibrate Now!");
        calibrate_ui.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                calibrate_uiActionPerformed(evt);
            }
        });

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
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addComponent(reset_daq_ui)
                        .addGap(10, 10, 10)
                        .addComponent(jLabel27)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(dev_name_ui, javax.swing.GroupLayout.PREFERRED_SIZE, 54, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(calibrate_ui))
                .addContainerGap(175, Short.MAX_VALUE))
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
                .addComponent(calibrate_ui)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 130, Short.MAX_VALUE)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(reset_daq_ui)
                    .addComponent(jLabel27)
                    .addComponent(dev_name_ui, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        jPanel7.getAccessibleContext().setAccessibleName("Calibration Setup");

        tabbed_panel.addTab(" Hardware Calibration", jPanel2);

        jLabel4.setText("TIRF Calibration:");

        jLabel16.setText("FRAP Calibration:");

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
                    .addComponent(tabbed_panel)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel16)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(photobleaching_calibration_sign))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel4)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(tirf_calibration_sign)))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
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
                .addComponent(tabbed_panel)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void tabbed_panelStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_tabbed_panelStateChanged
        update_cur_mode_based_on_tab();
    }//GEN-LAST:event_tabbed_panelStateChanged

    private void calibrate_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_calibrate_uiActionPerformed

        boolean daq_running = is_daq_running.get();
        boolean calibrate_pressed = calibrate_ui.isSelected();

        if (daq_running && calibrate_pressed) {
            JOptionPane.showMessageDialog(null,
                "The DAQ boad is being used by other program.");
            calibrate_ui.setSelected(false);
        } else if (daq_running && !calibrate_pressed) {
            stop_calibration();
            calibrate_ui.setText("Calibrate Now!");
        } else if (!daq_running && calibrate_pressed) {
            run_calibration();
            calibrate_ui.setText("Stop Calibration");
        } else {}

    }//GEN-LAST:event_calibrate_uiActionPerformed

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

    private void roi_manager_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_roi_manager_uiActionPerformed
        DualAxisMirrorPlugin.showRoiManager();

    }//GEN-LAST:event_roi_manager_uiActionPerformed

    /*    */
    
    private void submit_circles_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_submit_circles_uiActionPerformed

        if (!is_calibration_there())
            return;

        boolean daq_running = is_daq_running.get();
        boolean submit_pressed = submit_circles_ui.isSelected();

        if (daq_running && submit_pressed) {
            JOptionPane.showMessageDialog(null,
                "The DAQ boad is being used by other program.");
            submit_circles_ui.setSelected(false);
        } else if (daq_running && !submit_pressed) {
            stop_submit_circles();
            submit_circles_ui.setText("Submit Circles");
        } else if (!daq_running && submit_pressed) {
            start_submit_circles();
            submit_circles_ui.setText("Cancel");
        } else {}
    }//GEN-LAST:event_submit_circles_uiActionPerformed

    private void save_circle_maps_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_save_circle_maps_uiActionPerformed

        Thread th = new Thread("Projector calibration thread") {
            @Override
            public void run() {
                final boolean liveModeRunning = app_.isLiveModeOn();
                app_.enableLiveMode(false);

                String old_cam = core_.getCameraDevice();
                try {
                    core_.setCameraDevice(camera_name_ui1.getSelectedItem().toString());
                } catch (Exception ex) {
                    Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
                }

                final List<String> all_volts = new ArrayList<String>();
                for (int i=0; i < tirf_loops_model.size(); ++i) {
                    all_volts.addAll(((TIRFCircle)(tirf_loops_model.get(i))).volts);
                }

                short max_image[] = null;

                try {
                    core_.snapImage();
                    TaggedImage timg = core_.getTaggedImage();
                    max_image = (short[]) timg.pix;
                } catch (Exception ex) {
                    Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
                }

                // Compare to max_image and save the maximum value for each pixel
                String prefix = "tirf_map_";
                for (int i = 0, cnt = 0; i < all_volts.size(); i += 2, cnt++) {
                    Util.set_voltage(cur_mode.daq_dev_str,
                        Double.valueOf(all_volts.get(i)),
                        Double.valueOf(all_volts.get(i + 1)));
                    try {
                        core_.snapImage();
                        TaggedImage timg = core_.getTaggedImage();
                        app_.displayImage(timg);

                        //short image[] = (short[]) core_.getImage();
                        short image[] = (short[]) timg.pix;

                        ImageProcessor ip = ImageUtils.makeProcessor(core_, image);
                        ImagePlus imgp = new ImagePlus("", ip);
                        FileSaver fs = new FileSaver(imgp);
                        fs.saveAsTiff(".\\"+prefix+String.format("%04d", cnt)+".tiff");
                            /*
                            for (int q = 0; q < image.length; q++) {
                                if (image[q] > max_image[q]) {
                                    max_image[q] = image[q];
                                }
                            }
                            */
                        } catch (Exception ex) {
                            Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }

                    JOptionPane.showMessageDialog(null
                        , "Supercomposed image has been saved.");
                    app_.enableLiveMode(liveModeRunning);

                    try {
                        core_.setCameraDevice(old_cam);
                    } catch (Exception ex) {
                        Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            };
            th.start();

    }//GEN-LAST:event_save_circle_maps_uiActionPerformed

    private TIRFCircle create_circle_obj(HashMap<Polygon, AffineTransform> poly_mapping,
                                         int center_x,
                                         int center_y,
                                         int radius_um,
                                         int num_dots,
                                         double frequency)
  {
        TIRFCircle tc = new TIRFCircle();
        List<Double> circle_px = create_circle_dots((Integer)center_x,
                                                    (Integer)center_y,
                                                    num_dots,
                                                    radius_um/cur_mode.um_per_pix);
        if (circle_px == null)
            return null;
        
        List<String> transformed_points = new ArrayList<String>();
        
        for (int i = 0; i < circle_px.size() - 1; i += 2) {
            Point2D.Double p = new Point2D.Double(circle_px.get(i), circle_px.get(i + 1));
            Point2D.Double trans_p = Util.transformPoint(poly_mapping, p);

            String x = String.format("%.1f", (Double)trans_p.x);
            String y = String.format("%.1f", (Double)trans_p.y);
           
            transformed_points.add(x);
            transformed_points.add(y);        
        }
        tc.volts = transformed_points;
        tc.radius_um = (Integer)radius_um;
        tc.circle_frequency = (Double)frequency;
        tc.center_x = (Integer)center_x;
        tc.center_y = (Integer)center_y;
        
        return tc;
        //tirf_loops_model.addElement(tc);
    }
    
    private void add_circle_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_add_circle_uiActionPerformed

        TIRFCircle tc = new TIRFCircle();

        int num_dots = (Integer)circle_samples_ui.getValue();
        double radius_pix = ((Integer)circle_radius_ui.getValue()).doubleValue() /
                            cur_mode.um_per_pix;
        List<Double> circle_px = create_circle_dots(
                    (Integer)center_input_x_ui.getValue(),
                    (Integer)center_input_y_ui.getValue(),
                    num_dots,
                    radius_pix);
        if (circle_px == null)
            return;
        
        List<String> transformed_points = new ArrayList<String>();
        
        for (int i = 0; i < circle_px.size() - 1; i += 2) {
            Point2D.Double p = new Point2D.Double(circle_px.get(i), circle_px.get(i + 1));
            Point2D.Double trans_p = Util.transformPoint(cur_mode.poly_mapping, p);

            String x = String.format("%.1f", (Double)trans_p.x);
            String y = String.format("%.1f", (Double)trans_p.y);
           
            transformed_points.add(x);
            transformed_points.add(y);        
        }
        tc.volts = transformed_points;
        tc.radius_um = (Integer)circle_radius_ui.getValue();
        tc.circle_frequency = (Double)circle_frequency_ui.getValue();
        tc.center_x = (Integer)center_input_x_ui.getValue();
        tc.center_y = (Integer)center_input_y_ui.getValue();
        
        tirf_loops_model.addElement(tc);
    }//GEN-LAST:event_add_circle_uiActionPerformed

    private void move_circle_down_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_move_circle_down_uiActionPerformed
        int selected = tirf_loops_ui.getSelectedIndex();
        if (selected == -1 || selected == tirf_loops_model.size()-1)
        return;
        Object selected_item = (TIRFCircle) tirf_loops_model.get(selected);
        Object next_item = (TIRFCircle) tirf_loops_model.get(selected+1);

        tirf_loops_model.set(selected, next_item);
        tirf_loops_model.set(selected+1, selected_item);

        tirf_loops_ui.setSelectedIndex(selected+1);
    }//GEN-LAST:event_move_circle_down_uiActionPerformed

    private void move_circle_up_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_move_circle_up_uiActionPerformed
        int selected = tirf_loops_ui.getSelectedIndex();
        if (selected == -1 || selected == 0)
        return;
        Object selected_item = (TIRFCircle) tirf_loops_model.get(selected);
        Object pre_item = (TIRFCircle) tirf_loops_model.get(selected-1);

        tirf_loops_model.set(selected, pre_item);
        tirf_loops_model.set(selected-1, selected_item);

        tirf_loops_ui.setSelectedIndex(selected-1);
    }//GEN-LAST:event_move_circle_up_uiActionPerformed

    private void remove_circle_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_remove_circle_uiActionPerformed
        int selected = tirf_loops_ui.getSelectedIndex();
        if (selected == -1)
            return;
        tirf_loops_model.remove(selected);
    }//GEN-LAST:event_remove_circle_uiActionPerformed

    private void freerun_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_freerun_uiActionPerformed

        
        if (!is_calibration_there())
            return;
        
        
        boolean daq_running = is_daq_running.get();
        boolean freerun_pressed = freerun_ui.isSelected();

        if (daq_running && freerun_pressed) {
            JOptionPane.showMessageDialog(null,
                "The DAQ boad is being used by other program.");
            freerun_ui.setSelected(false);
        } else if (daq_running && !freerun_pressed) {
            stop_freerun();
            freerun_ui.setText("Free Run");
        } else if (!daq_running && freerun_pressed) {
            start_freerun();
            freerun_ui.setText("Cancel");
        } else {}
    }//GEN-LAST:event_freerun_uiActionPerformed

    private void remove_pa_row_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_remove_pa_row_uiActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_remove_pa_row_uiActionPerformed

    private void start_pa_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_start_pa_uiActionPerformed
        
    }//GEN-LAST:event_start_pa_uiActionPerformed

    private void add_pa_row_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_add_pa_row_uiActionPerformed
        DefaultTableModel model = (DefaultTableModel) pa_table.getModel();
        model.addRow(new Object[]{"0", "0", "0", "0"});
    }//GEN-LAST:event_add_pa_row_uiActionPerformed

    private void add_property_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_add_property_uiActionPerformed
        DefaultTableModel model = (DefaultTableModel) property_table.getModel();
        model.addRow(new Object[]{"pick a property", "", ""});
        
        JComboBox property_combo = new JComboBox();
        JComboBox property_value_combo_no_pa = new JComboBox();
        JComboBox property_value_combo_pa = new JComboBox();
        
        ArrayList<String> dev_prop_list = new ArrayList<String>();
        StrVector devices = core_.getLoadedDevices();
        for (int i =0; i < devices.size(); i++) {
            try {
                StrVector dev_prop = core_.getDevicePropertyNames(devices.get(i));
                for (int j=0; j <dev_prop.size(); j++) {
                    String combination = devices.get(i) + "-" + dev_prop.get(j);
                    dev_prop_list.add(combination);
                }
                 
            } catch (Exception ex) {
                Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
            }
            
        }
        property_combo.setModel(new DefaultComboBoxModel(dev_prop_list.toArray())); 
             
        TableColumn property_column = property_table.getColumnModel().getColumn(0);
        property_column.setCellEditor(new DefaultCellEditor(property_combo));
    }//GEN-LAST:event_add_property_uiActionPerformed

    private void input_volt_set_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_input_volt_set_uiActionPerformed
        final List<String> args = new ArrayList<String>();
        args.add(cur_mode.daq_dev_str);
        args.add(Double.toString((Double)input_volt_x_ui.getValue()));
        args.add(Double.toString((Double)input_volt_y_ui.getValue()));
                    
        //run_daq_program("two_ao_update.exe", args, true);
        Util.run_external_program("two_ao_update.exe", args);
    }//GEN-LAST:event_input_volt_set_uiActionPerformed

    int start_radius_default = -1;
    int end_radius_default = -1;
    int step_size_default = -1;
    int samples_default = 50;
    double frequency_default = 20;
    
    private void add_circle_range_guiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_add_circle_range_guiActionPerformed
        // TODO add your handling code here:
        if (!is_calibration_there()) {
             return;   
        }
        String start_radius_text = "";
        String end_radius_text = "";
        String step_size_text = "";
        String samples_text = "";
        String frequency_text = "";
        
        if (start_radius_default > 0)
            start_radius_text = Integer.toString(start_radius_default);
        if (end_radius_default > 0)
            end_radius_text = Integer.toString(end_radius_default);
        if (step_size_default > 0)
            step_size_text = Integer.toString(step_size_default);
        if (samples_default > 0)
            samples_text = Integer.toString(samples_default);
        if (frequency_default > 0)
            frequency_text = Double.toString(frequency_default);
            
        JTextField start_radius = new JTextField(start_radius_text);
        JTextField end_radius = new JTextField(end_radius_text);
        JTextField step_size = new JTextField(step_size_text);
        JTextField samples = new JTextField(samples_text);
        JTextField frequency = new JTextField(frequency_text);
        
        Object[] parameters = {
            "Start Radius(um):", start_radius,
            "End Radius(um):", end_radius,
            "Radius Step(um):", step_size,
            "# points on a circle:", samples,
            "Circle Freq(Hz):", frequency
        };

        int option = JOptionPane.showConfirmDialog(null, parameters, "Add circles in batch", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {        
            int s_r = Integer.parseInt(start_radius.getText());
            int e_r = Integer.parseInt(end_radius.getText());
            int step = Integer.parseInt(step_size.getText());
            int dots = Integer.parseInt(samples.getText());
            double freq = Double.parseDouble(frequency.getText());
            int c_x = (Integer) center_input_x_ui.getValue();
            int c_y = (Integer) center_input_y_ui.getValue();
            
            for (int i = s_r; i <= e_r; i += step) {
                TIRFCircle c = create_circle_obj(cur_mode.poly_mapping,
                                               c_x,
                                               c_y,
                                               i,
                                               dots,
                                               freq);
                tirf_loops_model.addElement(c);
            }
            start_radius_default = s_r;
            end_radius_default = e_r;
            step_size_default = step;
            samples_default = dots;
            frequency_default = freq;
        }
    }//GEN-LAST:event_add_circle_range_guiActionPerformed

    private void clear_circle_uiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clear_circle_uiActionPerformed
        // TODO add your handling code here:
        tirf_loops_model.clear();
    }//GEN-LAST:event_clear_circle_uiActionPerformed


    

    private Preferences getCalibrationNode() {
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
    
    private Process run_daq_program(String prog_name, List<String> args, boolean will_done) {
        try {
            args.add(0, Util.jar_path() + File.separator + prog_name);          
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
        return daq_proc;
    }

    private void saveToDisk(ExpMode mode) {
        Preferences p = getCalibrationNode();
        JavaUtils.putObjectInPrefs(p, mode.mode_name, mode);
    }

    private void update_cur_mode_based_on_tab() {
        int tab_ind = tabbed_panel.getSelectedIndex();
        if (tab_ind < 2) {
            cur_mode = mode_map.get(mode_str_array.get(tab_ind));
        }
    }
    private boolean is_calibration_there() {
        if (cur_mode.poly_mapping == null) {
            JOptionPane.showMessageDialog(null, "No Calibration Data");
            return false;
        }
        return true;
    }
    
    private List<Double> create_circle_dots(int center_x,
                                            int center_y,
                                            int num_dots,
                                            double radius_pix)
    {
        
        if (!is_calibration_there()) {
            return null;
        }
        
        List<Double> ret = new ArrayList<Double>();

        //int num_dots = (Integer)circle_samples_ui.getValue();
        //double radius = ((Integer)circle_radius_ui.getValue()).doubleValue() /
        //             cur_mode.um_per_pix;

        double unit_angle = (360.0 / num_dots) * (Math.PI / 180.0);

        for (int i = 0; i < num_dots; i++) {
            double dot_x = Math.cos(unit_angle * i) * radius_pix + center_x;
            double dot_y = Math.sin(unit_angle * i) * radius_pix + center_y;

            ret.add(dot_x);
            ret.add(dot_y);

        }
        //System.out.print(ret.toString());
        return ret;
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
         
        
    private void stop_freerun() {
        stop_daq_proc();
        is_daq_running.set(false);
    }
    
    private void start_freerun() {
        is_daq_running.set(true);
        
        int num_dots = (Integer)circle_samples_ui.getValue();
        double radius_pix = ((Integer)circle_radius_ui.getValue()).doubleValue() /
                     cur_mode.um_per_pix;
        
        final List<Double> combined = create_circle_dots(
                (Integer) center_input_x_ui.getValue(),
                (Integer) center_input_y_ui.getValue(),
                num_dots,
                radius_pix);
                
        
        final List<String> transformed_points = new ArrayList<String>();

        
        for (int i = 0; i < combined.size() - 1; i += 2) {
            Point2D.Double p = new Point2D.Double(combined.get(i), combined.get(i + 1));
            Point2D.Double trans_p = Util.transformPoint(cur_mode.poly_mapping, p);

            Double truncated_x = BigDecimal.valueOf(trans_p.x)
                    .setScale(1, RoundingMode.HALF_UP).doubleValue();
            Double truncated_y = BigDecimal.valueOf(trans_p.y)
                    .setScale(1, RoundingMode.HALF_UP).doubleValue();
            
            transformed_points.add(String.valueOf(truncated_x));
            transformed_points.add(String.valueOf(truncated_y));
            
            //transformed_points.add(String.valueOf(i));
            //transformed_points.add(String.valueOf(i+1));
        }
        
        int sampling_rate = (int) ((Integer) circle_samples_ui.getValue()
                * (Double) circle_frequency_ui.getValue());


        // example: run dev1/ao0,dev1/ao1 rate 6 x1 y1 x2 y2 x3 y3
        
//        List<String> args = Arrays.asList("dev1/ao2,dev1/ao3",
//                Integer.toString(sampling_rate),
//                Integer.toString(transformed_points.size()));
                
        List<String> args = Arrays.asList(cur_mode.daq_dev_str,
                Integer.toString(sampling_rate),
                Integer.toString(transformed_points.size()));

        transformed_points.addAll(0, args);

        Thread th = new Thread("Freerun thread") {
            @Override
            public void run() {     
                    run_daq_program(
                            "freerun.exe",
                            transformed_points,
                            false);
            }
        };
        th.start();
    }
    
    public void stop_calibration()
    {
        Util.is_stop_requested.set(true);
        is_daq_running.set(false);
    }

    public void run_calibration() 
    {
        is_daq_running.set(true);
        
        final boolean liveModeRunning = app_.isLiveModeOn();
        app_.enableLiveMode(false);

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
        if ((Double) um_per_pix_ui.getValue() == 0) {
            JOptionPane.showMessageDialog(null,
                    "Need to specify pixel size");
            return;
        }

        // populate properties in cur_mode from GUI
        cur_mode.mode_name = light_mode_drop.getSelectedItem().toString();
        cur_mode.camera_name = camera_name_ui.getSelectedItem().toString();
        cur_mode.daq_dev_str = x_axis_ui.getText() + "," + y_axis_ui.getText();
        cur_mode.um_per_pix = (Double) um_per_pix_ui.getValue();

        Point2D.Double zero_v = new Point2D.Double(0, 0);
        Point zero_p = Util.measureSpotOnCamera(core_, app_, cur_mode.daq_dev_str, zero_v);
        cur_mode.center_x = zero_p.x;
        cur_mode.center_y = zero_p.y;

        Thread calibrate_thread = new Thread("Calibration thread") {
            @Override
            public void run() {
                try {                   
                    AffineTransform first_mapping = Util.generateLinearMapping(core_, app_, cur_mode.daq_dev_str);
                    HashMap<Polygon, AffineTransform> poly_mapping = 
                            (HashMap<Polygon, AffineTransform>) Util.generateNonlinearMapping(
                            core_,
                            app_,
                            cur_mode.daq_dev_str,
                            first_mapping);
                    
                    boolean is_cancelled = (first_mapping == null) ||
                                           (poly_mapping  == null);
                    
                    if (!is_cancelled) {
                        cur_mode.first_mapping = first_mapping;
                        cur_mode.poly_mapping = poly_mapping;
                        saveToDisk(cur_mode);
                        mark_calibration_label(cur_mode.mode_name);
                        if (cur_mode.mode_name.equals(mode_str_array.get(0))) {
                            original_center_x_ui.setText(Integer.toString(cur_mode.center_x));
                            original_center_y_ui.setText(Integer.toString(cur_mode.center_y));
                            center_input_x_ui.setValue(Integer.valueOf(cur_mode.center_x));
                            center_input_y_ui.setValue(Integer.valueOf(cur_mode.center_y));
                        }
                    }

                    app_.enableLiveMode(liveModeRunning);
                    JOptionPane.showMessageDialog(null,
                            "Calibration " + (is_cancelled ?  
                            "canceled." : "finished."));
                    
                             
                } catch (HeadlessException e) {
                    ReportingUtils.showError(e);
                } catch (RuntimeException e) {
                    ReportingUtils.showError(e);          
                } finally {
                    stop_calibration();
                    calibrate_ui.setText("Calibrate Now!");
                    Util.is_stop_requested.set(false);
                }
            }
        };
        calibrate_thread.start();
    }
    
    private void stop_submit_circles() {
        stop_daq_proc();
        is_daq_running.set(false);
    }
    
    private void start_submit_circles() {

        is_daq_running.set(true);
        // prepare arguments before calling an external program
        final List<String> args = new ArrayList<String>();

        File f = null;
        try {
            f = File.createTempFile("tmp", ".csv");
            //f.deleteOnExit();
            
            BufferedWriter bw = new BufferedWriter(new FileWriter(f));
            for (int i = 0; i < tirf_loops_model.size(); ++i) {
                Double dt = 
                       1.0 / (((TIRFCircle)(tirf_loops_model.get(i))).circle_frequency
                * ((TIRFCircle)(tirf_loops_model.get(i))).volts.size() / 2);
                List<String> x_arr = new ArrayList<String>();
                List<String> y_arr = new ArrayList<String>();
                List<String> volt_arr = ((TIRFCircle)(tirf_loops_model.get(i))).volts;
                for (int j = 0; j < volt_arr.size(); j+=2) {
                    x_arr.add(volt_arr.get(j));
                    y_arr.add(volt_arr.get(j+1));
                }
                StringBuilder x_builder = new StringBuilder();
		StringBuilder y_builder = new StringBuilder();
                x_builder.append(String.format("%f", dt.doubleValue()));
                y_builder.append(String.format("%f", dt.doubleValue()));
                for(String v : x_arr){
                    x_builder.append(",");
                    x_builder.append(v);
                }
                for(String v : y_arr){
                    y_builder.append(",");
                    y_builder.append(v);
                }
                String x_st = x_builder.toString();
                String y_st = y_builder.toString();
                bw.write(x_st);
                bw.write("\n");
                bw.write(y_st);
                bw.write("\n");
            }
            bw.close();
            
        } catch(Exception e) {
            // if any error occurs
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                "Cannot create a temp file. Try run Micro-Manager as admin.");
            is_daq_running.set(false);
            return;
        }
        
        args.add(f.getAbsolutePath());
        args.add("0");  // number of triggers. 0 makes it wait forever.
        args.add(cur_mode.daq_dev_str);
        args.add("/Dev1/PFI0");    
        
        
        /*
        args.add(cur_mode.daq_dev_str);
        args.add("/Dev1/PFI0");
        args.add("2");
        Double sampling_rate = ((TIRFCircle)(tirf_loops_model.get(0))).circle_frequency
                * ((TIRFCircle)(tirf_loops_model.get(0))).volts.size() / 2;
        
        args.add(Integer.toString(sampling_rate.intValue()));
        args.add(Integer.toString(tirf_loops_model.size()));
        args.add(Integer.toString(((TIRFCircle)(tirf_loops_model.get(0))).volts.size() / 2));

        for (int i=0; i < tirf_loops_model.size(); ++i) {
                    args.addAll(((TIRFCircle)(tirf_loops_model.get(i))).volts);
        }
        */
        
        Thread th = new Thread("Submit circles thread") {
            @Override
            public void run() {
                run_daq_program(
                        "ao_patterns_triggered.exe",
                        args, false);
            }
        };
        th.start();
    }
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
    private javax.swing.JButton add_circle_range_gui;
    private javax.swing.JButton add_circle_ui;
    private javax.swing.JButton add_pa_row_ui;
    private javax.swing.JButton add_property_ui;
    private javax.swing.JToggleButton calibrate_ui;
    private javax.swing.JComboBox camera_name_ui;
    private javax.swing.JComboBox camera_name_ui1;
    private javax.swing.JSpinner center_input_x_ui;
    private javax.swing.JSpinner center_input_y_ui;
    private javax.swing.JSpinner circle_frequency_ui;
    private javax.swing.JSpinner circle_radius_ui;
    private javax.swing.JSpinner circle_samples_ui;
    private javax.swing.JButton clear_circle_ui;
    private javax.swing.JTextField dev_name_ui;
    private javax.swing.JToggleButton freerun_ui;
    private javax.swing.JButton input_volt_set_ui;
    private javax.swing.JSpinner input_volt_x_ui;
    private javax.swing.JSpinner input_volt_y_ui;
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
    private javax.swing.JLabel jLabel36;
    private javax.swing.JLabel jLabel37;
    private javax.swing.JLabel jLabel38;
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
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JScrollPane jScrollPane4;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JSpinner jSpinner1;
    private javax.swing.JSpinner jSpinner2;
    private javax.swing.JSpinner jSpinner3;
    private javax.swing.JSpinner jSpinner5;
    private javax.swing.JTable jTable1;
    private javax.swing.JComboBox light_mode_drop;
    private javax.swing.JButton move_circle_down_ui;
    private javax.swing.JButton move_circle_up_ui;
    private javax.swing.JLabel original_center_x_ui;
    private javax.swing.JLabel original_center_y_ui;
    private javax.swing.JTable pa_table;
    private javax.swing.JLabel photobleaching_calibration_sign;
    private javax.swing.JButton point_shoot_button;
    private javax.swing.JTextField point_shoot_x;
    private javax.swing.JTextField point_shoot_y;
    private javax.swing.JTable property_table;
    private javax.swing.JButton remove_circle_ui;
    private javax.swing.JButton remove_pa_row_ui;
    private javax.swing.JButton remove_property_ui;
    private javax.swing.JButton reset_daq_ui;
    private javax.swing.JButton roi_manager_ui;
    private javax.swing.JButton save_circle_maps_ui;
    private javax.swing.JButton start_pa_ui;
    private javax.swing.JToggleButton submit_circles_ui;
    private javax.swing.JTabbedPane tabbed_panel;
    private javax.swing.JLabel tirf_calibration_sign;
    private javax.swing.JList tirf_loops_ui;
    private javax.swing.JSpinner um_per_pix_ui;
    private javax.swing.JTextField x_axis_ui;
    private javax.swing.JTextField y_axis_ui;
    // End of variables declaration//GEN-END:variables
}
