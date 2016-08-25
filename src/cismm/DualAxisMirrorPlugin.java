package cismm;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import mmcorej.CMMCore;
import org.micromanager.api.ScriptInterface;
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author phsiao
 */
public class DualAxisMirrorPlugin implements org.micromanager.api.MMPlugin {

    private ScriptInterface app_;
    private CMMCore core_;
   
    private MirrorControlForm frame_= null;
    
    private final String package_path = "/cismm/NI_daq_bin/";
    private final String out_dir = "mmplugins/";
   
    List<String> daq_bin_list = Arrays.asList("two_ao_update.exe",
                                              "daq_trigger.exe",
                                              "freerun.exe",
                                              "reset_daq.exe",
                                              "ao_patterns_triggered.exe");
    
    public void create_daq_bins()
    {
        InputStream is = null;
        OutputStream os = null;
        
        for(String bin_file: daq_bin_list)
        {
            try {
                is = getClass().getResource(package_path + bin_file).openStream();
                os = new FileOutputStream(out_dir + bin_file);
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
    public void del_daq_bins() {
        for (String file: daq_bin_list)
        {
            File f = new File(out_dir + file);
            f.delete();
        }     
    }
    
    /*
    public DualAxisMirrorPlugin() {
        create_daq_bins();
    }
    */
    @Override
    public void dispose() {
        if (frame_ != null) {
            frame_.cleanup();
            frame_.setVisible(false);
            frame_.dispose();
            frame_ = null;
        }
        
        del_daq_bins();     
    }

    @Override
    public void setApp(ScriptInterface si) {
        app_ = si;
        core_ = app_.getMMCore();
        create_daq_bins();
    }

    @Override
    public void show() {
        if (frame_ == null) {
            frame_ = new MirrorControlForm(core_, app_, daq_bin_list);
            
        }
        else {
         //frame_.setPlugin(this);
         frame_.toFront();
        }
        frame_.setVisible(true);
    }

    @Override
    public String getDescription() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getInfo() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getVersion() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getCopyright() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
}
