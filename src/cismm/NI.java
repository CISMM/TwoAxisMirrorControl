/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cismm;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;

/**
 *
 * @author phsiao
 */
public class NI {
    public static double min_v_x = -10;
    public static double max_v_x = 10;
    public static double min_v_y = -10;
    public static double max_v_y = 10;
    
    public static double v_range_x = max_v_x - min_v_x;
    public static double v_range_y = max_v_y - min_v_y;
    
    public static Process   daq_proc = null;
//    private static AtomicBoolean daq_state = new AtomicBoolean(false);
//    
//    public static void set_daq_state(boolean state) {
//        daq_state.set(state);
//    }
//    public static boolean get_daq_state() {
//        return daq_state.get();
//    }
    public static boolean is_daq_running() 
    {
        if (daq_proc == null)
            return false;
        try 
        {
            daq_proc.exitValue();
            return false;
        } 
        catch(IllegalThreadStateException e) 
        {
            return true;
        }
    }
    
    public static void run_daq_program(String prog, List<String> args) {
        if (!is_daq_running())
            daq_proc = Util.run_external_program(prog, args);
        else
            JOptionPane.showMessageDialog(null, "DAQ is being used.");
    }
    public static void force_stop_daq() {
       if (daq_proc != null) {
            daq_proc.destroy();
            try {
                daq_proc.waitFor();
            } catch (InterruptedException ex) {
                Logger.getLogger(MirrorControlForm.class.getName()).log(Level.SEVERE, null, ex);
            }
        }     
    }
    
    public static void cleanup() {
        force_stop_daq();
    }
}
