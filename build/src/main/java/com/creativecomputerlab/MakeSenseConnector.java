
import processing.core.*; 
import processing.data.*; 
import processing.event.*; 
import processing.opengl.*; 

import controlP5.*;  
import com.codeminders.hidapi.HIDDeviceInfo; 
import com.codeminders.hidapi.HIDManager; 
import java.util.*; 

import com.codeminders.hidapi.*; 

import java.util.HashMap; 
import java.util.ArrayList; 
import java.io.File; 
import java.io.BufferedReader; 
import java.io.PrintWriter; 
import java.io.InputStream; 
import java.io.OutputStream; 
import java.io.IOException;

import java.net.InetSocketAddress;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import com.creativecomputerlab.*;

public class MakeSenseConnector extends PApplet {

WSServer server;

ControlP5 cp5;

One_channel channels[] = new One_channel[8]; 
Control_panel control_panel;

HIDDevice target_device=null;

public void init_device() {
  HIDDeviceInfo[] dev_list=filter_HID_ID_usage(0x04D8, 0xF46A, 0x00, 0x01);
  if (dev_list!=null && dev_list.length>0) {
    //println(dev_list);   
    try {
      target_device=dev_list[0].open();
	  server.setDevice(target_device);
    }
    catch (Exception ex) {
      ex.printStackTrace();
    }
  } else {
    println("DEV not found");
  }
}

public void setup() {
  server = new WSServer(8282, null);
  server.start();
  println("started web server");

	init_HID();
  //print_HID_devices();

  init_device();
  frameRate(20);

  size(1000, 700);
  cp5 = new ControlP5(this);
  for (int i=0; i<8; i++) {
    channels[i]=new One_channel(i, 100+i*100, 60);
    channels[i].init_P5();
  }
  control_panel=new Control_panel(900, 60);
  control_panel.init_P5();
  
  

}

public void draw() {
  background(0);
  if (target_device!=null) {
    byte buf[]=new byte[16];
    buf[0]=3;
    buf[1]=(byte)'R';
    HID_write_to_device(buf, target_device);
    buf = HID_read_from_device(target_device);
    //println(buf);
    if (buf!=null) {
      if (buf[0]==3 && buf[1]==(byte)'R') {
		// First 4 channels are input channels. Second 4 channels (4-7) are output and get set by web socket server.
        for (int i=0; i<4; i++) {
          channels[i].value_slider.setValue(buf[2+i]&0xFF);
          channels[i].tick_marker();
          channels[i].draw_active_area();
		  server.sendMessage("ch"+Integer.toString(i) + ":" +Integer.toString(buf[2+i]&0xFF));
        }
      } else {
        //println(buf);
      }
    }
  } else {
    init_device();
  }
  
}

public void delay_ms(int ms) {
  try {    
    Thread.sleep(ms);
  }
  catch(Exception e) {
  }
}

public void upload_key_settings(int key_num, int step) {
  byte data_src[]=channels[key_num].keycode;
  int addr=  16 + key_num * 64;
  int ptr=0;

  for (int j=0;j<step*2;j+=16) {
    byte addr_h = (byte)((addr+j)/256);
    byte addr_l = (byte)((addr+j)%256);
    byte buf[]=new byte[16];
    buf[0]=3;
    buf[1]=(byte)'E';
    buf[2]=(byte)'s';
    buf[3]=(byte)addr_h;
    buf[4]=(byte)addr_l;  //low addr. For whole page, low 4 bits must be 0;


    for (int i=5;i<5+8;i++) {
      buf[i]=data_src[ptr++];
    }
    HID_write_to_device(buf, target_device);

    buf[2]=(byte)'S';
    for (int i=5;i<5+8;i++) {
      buf[i]=data_src[ptr++];
    }
    HID_write_to_device(buf, target_device);
    delay_ms(5);  //wait for EEPROM
  }
}

public void upload_channel_settings() {
  int addr=  0;
  int ptr=0;

  byte addr_h = (byte)((addr+0)/256);
  byte addr_l = (byte)((addr+0)%256);
  byte buf[]=new byte[16];
  buf[0]=3;
  buf[1]=(byte)'E';
  buf[2]=(byte)'s';
  buf[3]=(byte)addr_h;
  buf[4]=(byte)addr_l;  //low addr. For whole page, low 4 bits must be 0;
  for (int i=0;i<8;i++) {
    One_channel channel_ptr = channels[i];
    int div_index=(int)(log(channel_ptr.div_now)/log(2));
    int setting_channel = ((div_index&0x07)<<4)|(((channel_ptr.repeat_mode)&0x03)<<0)|(((channel_ptr.hysteresis)&0x03)<<2);
    if (channel_ptr.joy_stick>0) {  //joystick_func
      setting_channel = (1<<7)|((channel_ptr.joy_stick&0x0F)<<3);
    }
    buf[i+5]=(byte)setting_channel;
  }
  HID_write_to_device(buf, target_device);
  buf[2]=(byte)'S';
  for (int i=5;i<5+8;i++) {
    if (i==5) {
      byte g1=0;
      if (control_panel.enable_kbd_checkbox.getState(0)) g1=(byte)(g1|1);
      buf[i]=g1;
    }
    else {
      buf[i]=0;  //nothing
    }
  }
  HID_write_to_device(buf, target_device);
  delay_ms(5);  //wait for EEPROM
}

final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
public static String bytesToHex(byte[] bytes) {
  char[] hexChars = new char[bytes.length * 3];
  for ( int j = 0; j < bytes.length; j++ ) {
    int v = bytes[j] & 0xFF;
    hexChars[j * 3] = hexArray[v >>> 4];
    hexChars[j * 3 + 1] = hexArray[v & 0x0F];
    hexChars[j * 3 + 2] = ' ';
  }
  return new String(hexChars);
}

public void retrive_channel_settings() {
  byte buf[]=retrive_data_16_bytes(0);
  println("data@"+0+":"+bytesToHex(buf));
  for (int i = 0; i < channels.length; i++) {
    One_channel channel_ptr = channels[i];
    int steps=0;
    int repeat=0;
    int hyst=0;
    if ((buf[i]&(1<<7))==0) {  //keyboard
      //bit 6-4 div (2,4,8,16,32) 1->2 2->4 3->8......
      //bit 3-2 hysteresis
      //bit 1-0 00 one shot 01 hold 02 repeat slow 11 repeat fast
      steps=((buf[i]>>4)&(0x07));
      if (steps<1 || steps>5) steps=0;
      else steps=steps-1;
      hyst=((buf[i]>>2)&(0x03));
      repeat=((buf[i]>>0)&(0x03));
      channel_ptr.repeat_mode=repeat;
      channel_ptr.hysteresis=hyst;
      channel_ptr.joy_stick=0;
    }
    else {
      int joystick=((buf[i]>>3)&(0x0F));
      if (joystick>10 || joystick<1) joystick=1;
      channel_ptr.joy_stick=joystick;
    }
    steps=1<<(steps+1);
    channel_ptr.update_div(steps, true);
  }
  //global settings
  if ((buf[8]&(1<<0))!=0) control_panel.enable_kbd_checkbox.activate(0);
  else control_panel.enable_kbd_checkbox.deactivate(0);
}

public void retrive_key_settings(int key_num, int step) {
  byte data_src[]=channels[key_num].keycode;
  One_channel channel_ptr = channels[key_num];
  int addr=  16 + key_num * 64;
  int ptr=0;

  if (channel_ptr.joy_stick!=0) return;

  for (int j=0;j<step*2;j+=16) {
    byte buf[]=retrive_data_16_bytes(addr+j);
    //println("data@"+(addr+j)+":"+bytesToHex(buf));
    for (int i=0;i<16;i++) {
      data_src[ptr++]=buf[i];
    }
  }
  channel_ptr.update_div(step, true);
}

public byte[] retrive_data_16_bytes(int addr) {
  byte addr_h = (byte)((addr+0)/256);
  byte addr_l = (byte)((addr+0)%256);
  byte buf[]=new byte[16];
  byte buf_r[]=new byte[16];
  byte buf_data[]=new byte[16];

  buf[0]=3;
  buf[1]=(byte)'E';
  buf[2]=(byte)'g';
  buf[3]=(byte)addr_h;
  buf[4]=(byte)addr_l;
  HID_write_to_device(buf, target_device);
  buf_r = HID_read_from_device(target_device);
  System.arraycopy(buf_r, 5, buf_data, 0, 8);
  buf[2]=(byte)'G';
  HID_write_to_device(buf, target_device);
  buf_r = HID_read_from_device(target_device);
  System.arraycopy(buf_r, 5, buf_data, 8, 8);
  //println("data@"+addr+":"+bytesToHex(buf_data));
  return buf_data;
}

// Bits in usbHidKeyboardInput.modifiers
final byte MODIFIER_NONE          =PApplet.parseByte((0));
final byte MODIFIER_CONTROL_LEFT  =PApplet.parseByte((1<<0));
final byte MODIFIER_SHIFT_LEFT    =PApplet.parseByte((1<<1));
final byte MODIFIER_ALT_LEFT      =PApplet.parseByte((1<<2));
final byte MODIFIER_GUI_LEFT      =PApplet.parseByte((1<<3));
final byte MODIFIER_CONTROL_RIGHT =PApplet.parseByte((1<<4));
final byte MODIFIER_SHIFT_RIGHT   =PApplet.parseByte((1<<5));
final byte MODIFIER_ALT_RIGHT     =PApplet.parseByte((1<<6));
final byte MODIFIER_GUI_RIGHT     =PApplet.parseByte((1<<7));

// Values for usbHidKeyboardInput.keyCodes
// Only the key codes for common keys are defined here. See Hut1_12.pdf for a full list.
final byte KEY_NONE               =PApplet.parseByte(0x00);
final byte KEY_A                  =PApplet.parseByte(0x04);
final byte KEY_B                  =PApplet.parseByte(0x05);
final byte KEY_C                  =PApplet.parseByte(0x06);
final byte KEY_D                  =PApplet.parseByte(0x07);
final byte KEY_E                  =PApplet.parseByte(0x08);
final byte KEY_F                  =PApplet.parseByte(0x09);
final byte KEY_G                  =PApplet.parseByte(0x0A);
final byte KEY_H                  =PApplet.parseByte(0x0B);
final byte KEY_I                  =PApplet.parseByte(0x0C);
final byte KEY_J                  =PApplet.parseByte(0x0D);
final byte KEY_K                  =PApplet.parseByte(0x0E);
final byte KEY_L                  =PApplet.parseByte(0x0F);
final byte KEY_M                  =PApplet.parseByte(0x10);
final byte KEY_N                  =PApplet.parseByte(0x11);
final byte KEY_O                  =PApplet.parseByte(0x12);
final byte KEY_P                  =PApplet.parseByte(0x13);
final byte KEY_Q                  =PApplet.parseByte(0x14);
final byte KEY_R                  =PApplet.parseByte(0x15);
final byte KEY_S                  =PApplet.parseByte(0x16);
final byte KEY_T                  =PApplet.parseByte(0x17);
final byte KEY_U                  =PApplet.parseByte(0x18);
final byte KEY_V                  =PApplet.parseByte(0x19);
final byte KEY_W                  =PApplet.parseByte(0x1A);
final byte KEY_X                  =PApplet.parseByte(0x1B);
final byte KEY_Y                  =PApplet.parseByte(0x1C);
final byte KEY_Z                  =PApplet.parseByte(0x1D);
final byte KEY_1                  =PApplet.parseByte(0x1E);
final byte KEY_2                  =PApplet.parseByte(0x1F);
final byte KEY_3                  =PApplet.parseByte(0x20);
final byte KEY_4                  =PApplet.parseByte(0x21);
final byte KEY_5                  =PApplet.parseByte(0x22);
final byte KEY_6                  =PApplet.parseByte(0x23);
final byte KEY_7                  =PApplet.parseByte(0x24);
final byte KEY_8                  =PApplet.parseByte(0x25);
final byte KEY_9                  =PApplet.parseByte(0x26);
final byte KEY_0                  =PApplet.parseByte(0x27);
final byte KEY_RETURN             =PApplet.parseByte(0x28);
final byte KEY_ESCAPE             =PApplet.parseByte(0x29);
final byte KEY_BACKSPACE          =PApplet.parseByte(0x2A);
final byte KEY_TAB                =PApplet.parseByte(0x2B);
final byte KEY_SPACE              =PApplet.parseByte(0x2C);
final byte KEY_MINUS              =PApplet.parseByte(0x2D);
final byte KEY_EQUAL              =PApplet.parseByte(0x2E);
final byte KEY_BRACKET_LEFT       =PApplet.parseByte(0x2F);
final byte KEY_BRACKET_RIGHT      =PApplet.parseByte(0x30);
final byte KEY_BACKSLASH          =PApplet.parseByte(0x31);
final byte KEY_EUROPE_1           =PApplet.parseByte(0x32);
final byte KEY_SEMICOLON          =PApplet.parseByte(0x33);
final byte KEY_APOSTROPHE         =PApplet.parseByte(0x34);
final byte KEY_GRAVE              =PApplet.parseByte(0x35);
final byte KEY_COMMA              =PApplet.parseByte(0x36);
final byte KEY_PERIOD             =PApplet.parseByte(0x37);
final byte KEY_SLASH              =PApplet.parseByte(0x38);
final byte KEY_CAPS_LOCK          =PApplet.parseByte(0x39);
final byte KEY_F1                 =PApplet.parseByte(0x3A);
final byte KEY_F2                 =PApplet.parseByte(0x3B);
final byte KEY_F3                 =PApplet.parseByte(0x3C);
final byte KEY_F4                 =PApplet.parseByte(0x3D);
final byte KEY_F5                 =PApplet.parseByte(0x3E);
final byte KEY_F6                 =PApplet.parseByte(0x3F);
final byte KEY_F7                 =PApplet.parseByte(0x40);
final byte KEY_F8                 =PApplet.parseByte(0x41);
final byte KEY_F9                 =PApplet.parseByte(0x42);
final byte KEY_F10                =PApplet.parseByte(0x43);
final byte KEY_F11                =PApplet.parseByte(0x44);
final byte KEY_F12                =PApplet.parseByte(0x45);
final byte KEY_PRINT_SCREEN       =PApplet.parseByte(0x46);
final byte KEY_SCROLL_LOCK        =PApplet.parseByte(0x47);
final byte KEY_PAUSE              =PApplet.parseByte(0x48);
final byte KEY_INSERT             =PApplet.parseByte(0x49);
final byte KEY_HOME               =PApplet.parseByte(0x4A);
final byte KEY_PAGE_UP            =PApplet.parseByte(0x4B);
final byte KEY_DELETE             =PApplet.parseByte(0x4C);
final byte KEY_END                =PApplet.parseByte(0x4D);
final byte KEY_PAGE_DOWN          =PApplet.parseByte(0x4E);
final byte KEY_ARROW_RIGHT        =PApplet.parseByte(0x4F);
final byte KEY_ARROW_LEFT         =PApplet.parseByte(0x50);
final byte KEY_ARROW_DOWN         =PApplet.parseByte(0x51);
final byte KEY_ARROW_UP           =PApplet.parseByte(0x52);
final byte KEY_NUM_LOCK           =PApplet.parseByte(0x53);
final byte KEY_KEYPAD_DIVIDE      =PApplet.parseByte(0x54);
final byte KEY_KEYPAD_MULTIPLY    =PApplet.parseByte(0x55);
final byte KEY_KEYPAD_SUBTRACT    =PApplet.parseByte(0x56);
final byte KEY_KEYPAD_ADD         =PApplet.parseByte(0x57);
final byte KEY_KEYPAD_ENTER       =PApplet.parseByte(0x58);
final byte KEY_KEYPAD_1           =PApplet.parseByte(0x59);
final byte KEY_KEYPAD_2           =PApplet.parseByte(0x5A);
final byte KEY_KEYPAD_3           =PApplet.parseByte(0x5B);
final byte KEY_KEYPAD_4           =PApplet.parseByte(0x5C);
final byte KEY_KEYPAD_5           =PApplet.parseByte(0x5D);
final byte KEY_KEYPAD_6           =PApplet.parseByte(0x5E);
final byte KEY_KEYPAD_7           =PApplet.parseByte(0x5F);
final byte KEY_KEYPAD_8           =PApplet.parseByte(0x60);
final byte KEY_KEYPAD_9           =PApplet.parseByte(0x61);
final byte KEY_KEYPAD_0           =PApplet.parseByte(0x62);
final byte KEY_KEYPAD_DECIMAL     =PApplet.parseByte(0x63);
final byte KEY_EUROPE_2           =PApplet.parseByte(0x64);
final byte KEY_APPLICATION        =PApplet.parseByte(0x65);
final byte KEY_POWER              =PApplet.parseByte(0x66);
final byte KEY_KEYPAD_EQUAL       =PApplet.parseByte(0x67);
final byte KEY_F13                =PApplet.parseByte(0x68);
final byte KEY_F14                =PApplet.parseByte(0x69);
final byte KEY_F15                =PApplet.parseByte(0x6A);
final byte KEY_CONTROL_LEFT       =PApplet.parseByte(0xE0);
final byte KEY_SHIFT_LEFT         =PApplet.parseByte(0xE1);
final byte KEY_ALT_LEFT           =PApplet.parseByte(0xE2);
final byte KEY_GUI_LEFT           =PApplet.parseByte(0xE3);
final byte KEY_CONTROL_RIGHT      =PApplet.parseByte(0xE4);
final byte KEY_SHIFT_RIGHT        =PApplet.parseByte(0xE5);
final byte KEY_ALT_RIGHT          =PApplet.parseByte(0xE6);
final byte KEY_GUI_RIGHT          =PApplet.parseByte(0xE7);

public String convert_keycode_to_str(byte modifier_byte, byte key_byte) {
  if (modifier_byte==0 && key_byte==0) return "EMPTY";
  String modifier_str="";
  for (int i=0;i<4;i++) {
    if ((modifier_byte&(1<<i))!=0) {
      if (modifier_str.length()>0) {
        modifier_str=modifier_str+"+"+rev_modifier_mapping_Simple[i];
      }
      else {
        modifier_str=rev_modifier_mapping_Simple[i];
      }
    }
  }
  String key_str=rev_mapping[key_byte&0xFF];
  if (key_str.length()>0) {
    if (modifier_str.length()>0) return modifier_str+"+"+key_str;
    else return key_str;
  }
  else {
    if (modifier_str.length()>0) return modifier_str;
    else return "EMPTY";
  }
}

public void customize(DropdownList ddl, String caption, String[] items) {
  // a convenience function to customize a DropdownList
  ddl.setBackgroundColor(color(190));
  ddl.setItemHeight(20);
  ddl.setBarHeight(15);
  ddl.captionLabel().set(caption);
  ddl.captionLabel().style().marginTop = 3;
  ddl.captionLabel().style().marginLeft = 3;
  ddl.valueLabel().style().marginTop = 3;
  ddl.clear();
  if (items!=null) {
    for (int i=0;i<items.length;i++) {
      ddl.addItem(items[i], i);
    }
  }
}

int keyboard_range[]= {
  0x04, 0x27+1, 0x28, 0x52+1, 0x53, 0x67+1, 0x68, 0x81+1, 0xE0, 0xE7+1
};

String[] report_LV2_keyboards= {
  "a-0", 
  "symbol&func", 
  "keypad", 
  "extension", 
  //"modifier"
};




private static final long serialVersionUID = 619732094067421147L;
HIDManager hid_mgr = null;

public int init_HID() {
  try {
    ClassPathLibraryLoader.loadNativeHIDLibrary();
    hid_mgr = HIDManager.getInstance();
  }
  catch (Exception ex) {
    ex.printStackTrace();
    return 0;
  }
  return 1;
}

public int print_HID_devices() {
  if (hid_mgr==null) return 0;
  try {
    for (HIDDeviceInfo info : hid_mgr.listDevices ()) {
      //println(info.getProduct_string()+" "+info.getVendor_id()+" "+info.getProduct_id());
      println(info);
    }
  }
  catch (Exception ex) {
    ex.printStackTrace();
    return 0;
  }
  return 1;
}

public HIDDeviceInfo[] filter_HID_ID_usage(int vid, int pid, int usage_page, int usage) {
  if (hid_mgr==null) return null;
  List<HIDDeviceInfo> dev_list = new ArrayList<HIDDeviceInfo>();
  try {
    for (HIDDeviceInfo info : hid_mgr.listDevices ()) {
      if (info.getVendor_id()==vid && info.getProduct_id()==pid) {
        try {
          HIDDevice try_device=info.open();
          byte buf[]=new byte[16];
          buf[0]=3;
          buf[1]=(byte)'R';
          HID_write_to_device(buf, try_device);
          buf = HID_read_from_device(try_device);
          //println(buf);
          if (buf!=null) {
            if (buf[0]==3 && buf[1]==(byte)'R') {
              println(info);
              dev_list.add(info);
            }
          }
          try_device.close();
        }
        catch (Exception ex) {
        }
      }
    }
  }
  catch (Exception ex) {
    ex.printStackTrace();
    return null;
  }

  HIDDeviceInfo[] dev_list_arr = new HIDDeviceInfo[ dev_list.size() ];
  dev_list.toArray( dev_list_arr );
  println("Found " + dev_list.size() + " devices");
  return dev_list_arr;
}

public void HID_write_to_device (byte data[], HIDDevice device) {
  try {
    if (device!=null) 
      device.write(data);
  }
  catch (Exception ex) {
    ex.printStackTrace();
    target_device=null;
  }
}
public byte[] HID_read_from_device (HIDDevice device) {
  byte buf[]=new byte[16];
  try {
    if (device!=null) {
      device.readTimeout(buf, 1000);
      return buf;
    }
  }
  catch (Exception ex) {
    ex.printStackTrace();
  }
  return null;
}

class Control_panel { 
  float x, y;
  //Slider value_slider;
  //DropdownList div_downlist;
  //Button set_settings, get_settings;
  Button set_EEPROM, get_EEPROM;
  Button reset_mark;
  Button apply_keys, clear_selection;
  DropdownList report_type, report_key, setting_slot;
  CheckBox modifier_checkbox;
  Button save_settings, load_settings;
  CheckBox enable_kbd_checkbox;

  boolean nokey_selected=true;

  Control_panel_ControlListener myListener = new Control_panel_ControlListener();

  Control_panel (float _x, float _y) {  
    x=_x;
    y=_y;
  }

  public void init_P5() {

    enable_kbd_checkbox=cp5.addCheckBox("global_setting");
    enable_kbd_checkbox.setPosition(x, y+30);
    enable_kbd_checkbox.setSize(20, 20);
    enable_kbd_checkbox.addItem("Default_Enable_KBD", 0);

    set_EEPROM=cp5.addButton("Set_EEPROM");
    set_EEPROM.setPosition(x, y+60);
    set_EEPROM.setSize(70, 20);
    set_EEPROM.addListener(myListener);

    get_EEPROM=cp5.addButton("Get_EEPROM");
    get_EEPROM.setPosition(x, y+90);
    get_EEPROM.setSize(70, 20);
    get_EEPROM.addListener(myListener);

    reset_mark=cp5.addButton("Reset_mark");
    reset_mark.setPosition(x, y+120);
    reset_mark.setSize(70, 20);
    reset_mark.addListener(myListener);

    clear_selection=cp5.addButton("Clear_SELECTION");
    clear_selection.setPosition(x, y+210+100);
    clear_selection.setSize(70, 20);
    clear_selection.addListener(myListener);

    apply_keys=cp5.addButton("Apply_keys");
    apply_keys.setPosition(x, y+180+100);
    apply_keys.setSize(70, 20);
    apply_keys.addListener(myListener);

    save_settings=cp5.addButton("Save_settings");
    save_settings.setPosition(x, y+380);
    save_settings.setSize(70, 20);
    save_settings.addListener(myListener);
    load_settings=cp5.addButton("Load_settings");
    load_settings.setPosition(x, y+410);
    load_settings.setSize(70, 20);
    load_settings.addListener(myListener);

    setting_slot=cp5.addDropdownList("Settings");
    setting_slot.setPosition(x+0, y+370);
    setting_slot.setSize(70, 160);
    for (int i=0;i<8;i++) {
      setting_slot.addItem("Setting"+i, i);
    }

    report_key = cp5.addDropdownList("key_list");
    report_key.setPosition(x+0, y+170+100);
    report_key.setSize(70, 160);
    customize(report_key, "no_key", null);
    report_key.addListener(myListener);

    report_type = cp5.addDropdownList("type_list");
    report_type.setPosition(x+0, y+150+100);
    report_type.setSize(70, 160);
    customize(report_type, "type", report_LV2_keyboards);
    report_type.addListener(myListener);


    modifier_checkbox = cp5.addCheckBox("modifier_checkBox");
    modifier_checkbox.setPosition(x, y+70+100);
    modifier_checkbox.setSize(10, 10);
    modifier_checkbox.setItemsPerRow(1);
    modifier_checkbox.setSpacingRow(5);
    modifier_checkbox.addItem("CTRL", 0);
    modifier_checkbox.addItem("SHIFT", 1);
    modifier_checkbox.addItem("ALT", 2);
    modifier_checkbox.addItem("GUI", 3);
  }

  int temp=0;

  class Control_panel_ControlListener implements ControlListener {
    int col;
    public void controlEvent(ControlEvent theEvent) {


      if (theEvent.getName().startsWith("Set_EEPROM")) {
        upload_channel_settings();
        for (int i=0;i<8;i++) {
          upload_key_settings(i, channels[i].div_now);
        }
      }

      if (theEvent.getName().startsWith("Get_EEPROM")) {
        retrive_channel_settings();
        for (int i=0;i<8;i++) {
          retrive_key_settings(i, channels[i].div_now);
        }
      }

      if (theEvent.getName().startsWith("type_list")) {
        int value=(int)(theEvent.getValue());
        customize(report_key, "no_key", Arrays.copyOfRange(rev_mapping, keyboard_range[value*2], keyboard_range[value*2+1]));
        nokey_selected=true;
      }

      if (theEvent.getName().startsWith("key_list")) {
        nokey_selected=false;
      }


      if (theEvent.getName().startsWith("Apply_keys")) {
        byte modifier_byte=0;
        byte key_byte=0;
        int type_value=(int)(report_type.getValue());
        int key_value=(int)(report_key.getValue());

        float modifier_array[]=modifier_checkbox.getArrayValue();
        for (int i=0;i<4;i++) 
          if (modifier_array[i]>0)  modifier_byte|=(1<<i);

        if (nokey_selected) key_byte=0;
        else key_byte=PApplet.parseByte(keyboard_range[type_value*2]+key_value);

        //println(modifier_byte+ "|" + key_byte+"{}"+type_value+key_value);

        for (int i=0;i<8;i++) {
          One_channel channel_ptr = channels[i];
          for (int j=0;j<channel_ptr.div_now;j++) {
            Toggle t = channel_ptr.key_checkbox.getItem(j);
            if (t.getState()) {
              channel_ptr.keycode[2*j]=modifier_byte;
              channel_ptr.keycode[2*j+1]=key_byte;
              t.setLabel(convert_keycode_to_str(channel_ptr.keycode[2*j], channel_ptr.keycode[2*j+1]));
            }
          }
        }
      }

      if (theEvent.getName().startsWith("Clear_SELECTION")) {
        for (int i=0;i<8;i++) {
          One_channel channel_ptr = channels[i];
          channel_ptr.key_checkbox.deactivateAll();
        }
      }

      if (theEvent.getName().startsWith("Save_settings")) {
        save_setting_to_file((int)setting_slot.getValue());
      }
      if (theEvent.getName().startsWith("Load_settings")) {
        load_setting_from_file((int)setting_slot.getValue());
      }
      if (theEvent.getName().startsWith("Reset_mark")) {
        for (int i=0;i<8;i++) {
          One_channel channel_ptr = channels[i];
          channel_ptr.reset_marker();
        }
      }

      //println(theEvent.getName());
      //println("my id" + id);
      //println(theEvent);
      // theEvent.getController().getValue());
      // col = (int)theEvent.getController().getValue();
      // x++;
    }
  }
}

class One_channel { 
  int id=-1; 
  float x, y;
  Slider value_slider;
  DropdownList div_downlist, repeat_downlist, hysteresis_downlist, joystick_downlist;
  One_channel_ControlListener myListener = new One_channel_ControlListener();
  CheckBox key_checkbox;
  byte keycode[] = new byte[64];
  int div_now=2;
  int repeat_mode=0;
  int hysteresis=0;
  int joy_stick=0;
  float marker_max=0;
  float marker_min=255;
  int active_area=0;


  One_channel (int _id, float _x, float _y) {  
    id = _id;
    x=_x;
    y=_y;

    value_slider=cp5.addSlider("Sensor"+id);

    joystick_downlist = cp5.addDropdownList("div-joy"+id);
    hysteresis_downlist = cp5.addDropdownList("div-hyst"+id);
    repeat_downlist = cp5.addDropdownList("div-repeat"+id);
    div_downlist = cp5.addDropdownList("div-dl"+id);

    key_checkbox = cp5.addCheckBox("key_checkBox"+id);
  }
  public void init_P5() {
    value_slider.setPosition(x, y);
    value_slider.setSize(20, 512);
    value_slider.setRange(0, 255);
    value_slider.setLock(true);

    div_downlist.setPosition(x-50, y+512+14).setSize(30, 70);
    div_downlist.captionLabel().set("Steps");
    div_downlist.addItem("2", 0);
    div_downlist.addItem("4", 1);
    div_downlist.addItem("8", 2);
    div_downlist.addItem("16", 3);
    div_downlist.addItem("32", 4);
    div_downlist.addListener(myListener);

    repeat_downlist.setPosition(x-50, y+512+34).setSize(60, 70);
    repeat_downlist.captionLabel().set("Mode");
    repeat_downlist.addItem("ONE SHOT", 0);
    repeat_downlist.addItem("HOLD", 1);
    repeat_downlist.addItem("REPEAT", 2);
    repeat_downlist.addItem("FAST REPEAT", 3);
    repeat_downlist.addListener(myListener); 

    hysteresis_downlist.setPosition(x-50, y+512+54).setSize(60, 70);
    hysteresis_downlist.captionLabel().set("HYST");
    hysteresis_downlist.addItem("VERY LOW", 0);
    hysteresis_downlist.addItem("LOW", 1);
    hysteresis_downlist.addItem("Medium", 2);
    hysteresis_downlist.addItem("HIGH", 3);
    hysteresis_downlist.addListener(myListener); 

    joystick_downlist.setPosition(x-50, y+512+74).setSize(60, 50);
    joystick_downlist.captionLabel().set("JOY/KBD");
    joystick_downlist.addItem("KEYBOARD", 0);
    joystick_downlist.addItem("X axis", 1);
    joystick_downlist.addItem("Y axis", 2);
    joystick_downlist.addItem("Z axis", 3);
    joystick_downlist.addItem("X rotation", 4);
    joystick_downlist.addItem("Y rotation", 5);
    joystick_downlist.addItem("Z rotation", 6);
    joystick_downlist.addItem("Button 1", 7);
    joystick_downlist.addItem("Button 2", 8);
    joystick_downlist.addItem("Button 3", 9);
    joystick_downlist.addItem("Button 4", 10);
    joystick_downlist.addListener(myListener); 

    key_checkbox.setPosition(x-50, y);

    key_checkbox.setSize(10, 10);
    key_checkbox.setItemsPerRow(1);
    key_checkbox.setSpacingRow(5);

    for (int i=0;i<32;i++) {
      key_checkbox.addItem(id+"0"+i, i);
    }
    for (int i=0;i<32;i++) {
      Toggle t = key_checkbox.getItem(i);
    }
    update_div(div_now, false);
  }

  public void update_div(int value, boolean update_dropdown) {
    for (int i=0;i<32;i++) {
      Toggle t = key_checkbox.getItem(i);
      t.setVisible(i<value);
      t.setLabel(convert_keycode_to_str(keycode[2*i], keycode[2*i+1]));
      if (i>=value) {
        t.setValue(false);
      }
    }
    int spacing = -(512/value)-10;
    key_checkbox.setSpacingRow(spacing);
    key_checkbox.setPosition(x-50, y-(512/value/2)-5+512);

    div_now=value;

    if (update_dropdown) {
      int div_index=(int)(log(div_now)/log(2))-1;
      if ((1<<(div_index+1))==div_now) div_downlist.setValue(div_index);
      repeat_downlist.setValue(repeat_mode);
      hysteresis_downlist.setValue(hysteresis);
      joystick_downlist.setValue(joy_stick);
    }
  }

  public void reset_marker() { 
    marker_max=0;
    marker_min=255;
  }

  public void tick_marker() {
    float value=value_slider.getValue();

    if (value>marker_max) marker_max=value;
    if (value<marker_min) marker_min=value;

    float y_pos_max=map(marker_max, 0, 255, y+512, y);
    float y_pos_min=map(marker_min, 0, 255, y+512, y);
    stroke(255);
    line (x-1, y_pos_max, x-1, y_pos_min);

    noStroke();
    fill(0x44);
    for (int i=1;i<div_now;i++) {
      float y_pos=map(i, 0, div_now, y+512, y);
      int range=256/div_now;
      int threshold=range>>(4-hysteresis);
      float threshold_pos=threshold*2-1;
      line (x-1, y_pos, x-10, y_pos);
      rect (x-2, y_pos-threshold_pos, -9, threshold_pos*2);
    }
  }

  public void draw_active_area() {
    noStroke();
    fill(0x88);

    int value=(int)value_slider.getValue();
    int range=256/div_now;
    int threshold=range>>(4-hysteresis);

    int level_low=(active_area*range)-threshold;
    int level_high=((active_area+1)*range)+threshold;

    if (value<(level_low) || value>(level_high)) {
      active_area=value*div_now/(256);
    }

    float y_pos=map(active_area, 0-.5f, div_now-.5f, y+512, y);
    rect (x-10, y_pos-5-2, -28, 10+4);
  }

  class One_channel_ControlListener implements ControlListener {
    int col;
    public void controlEvent(ControlEvent theEvent) {
      if (theEvent.getName().startsWith("div-dl")) {
        // set div number
        int value=2<<((int)(theEvent.getValue()));
        update_div(value, false);
      }

      if (theEvent.getName().startsWith("div-repeat")) {
        repeat_mode=(int)(theEvent.getValue());
      }

      if (theEvent.getName().startsWith("div-hyst")) {
        hysteresis=(int)(theEvent.getValue());
      }

      if (theEvent.getName().startsWith("div-joy")) {
        joy_stick=(int)(theEvent.getValue());
      }


      //println(theEvent.getName());
      //println("my id" + id);
      //println(theEvent);
      // theEvent.getController().getValue());
      // col = (int)theEvent.getController().getValue();
      // x++;
    }
  }
} 


String rev_mousebutton_mapping[]= {
  "LEFT", 
  "RIGHT", 
  "MIDDLE",
};

String rev_mousemove_mapping[]= {
  "X", 
  "Y", 
  "Wheel",
};

String rev_consumer_mapping[]= {
  "Home", 
  "KeyboardLayout", 
  "Search", 
  "Snapshot", 
  "VolumeUp", 
  "VolumeDown", 
  "Play/Pause", 
  "Fast Forward", 
  "Rewind", 
  "Scan Next Track", 
  "Scan Previous Track", 
  "Random Play",
  "Stop",
};

String rev_modifier_mapping[]= {
  "Lctrl", 
  "Lshift", 
  "Lalt", 
  "Lgui", 
  "Rctrl", 
  "Rshift", 
  "Ralt", 
  "Rgui",
};

String rev_modifier_mapping_Simple[]= {
  "Ctrl", 
  "Shift", 
  "Alt", 
  "Gui", 
};

String rev_mapping[]= {
  "", 
  "ErrorRollOver", 
  "POSTFail", 
  "ErrorUndefined", 
  "a", 
  "b", 
  "c", 
  "d", 
  "e", 
  "f", 
  "g", 
  "h", 
  "i", 
  "j", 
  "k", 
  "l", 
  "m", 
  "n", 
  "o", 
  "p", 
  "q", 
  "r", 
  "s", 
  "t", 
  "u", 
  "v", 
  "w", 
  "x", 
  "y", 
  "z", 
  "1", 
  "2", 
  "3", 
  "4", 
  "5", 
  "6", 
  "7", 
  "8", 
  "9", 
  "0", 
  "RETURN", 
  "ESCAPE", 
  "BACKSPACE", 
  "TAB", 
  "SPACE", 
  "-", 
  "=", 
  "[", 
  "]", 
  "\\", 
  "Non-US#", 
  ";", 
  "'", 
  "`", 
  ",", 
  ". and >", 
  "/", 
  "CapsLock", 
  "F1", 
  "F2", 
  "F3", 
  "F4", 
  "F5", 
  "F6", 
  "F7", 
  "F8", 
  "F9", 
  "F10", 
  "F11", 
  "F12", 
  "PrintScreen", 
  "ScrollLock", 
  "Pause", 
  "Insert", 
  "Home", 
  "PageUp", 
  "Delete Forward", 
  "End", 
  "PageDown", 
  "RightArrow", 
  "LeftArrow", 
  "DownArrow", 
  "UpArrow", 
  "NumLock", 
  "Keypad/", 
  "Keypad*", 
  "Keypad-", 
  "Keypad+", 
  "KeypadENTER", 
  "Keypad1", 
  "Keypad2", 
  "Keypad3", 
  "Keypad4", 
  "Keypad5", 
  "Keypad6", 
  "Keypad7", 
  "Keypad8", 
  "Keypad9", 
  "Keypad0", 
  "Keypad.", 
  "Non-US\\", 
  "Application", 
  "Power", 
  "Keypad=", 
  "F13", 
  "F14", 
  "F15", 
  "F16", 
  "F17", 
  "F18", 
  "F19", 
  "F20", 
  "F21", 
  "F22", 
  "F23", 
  "F24", 
  "Execute", 
  "Help", 
  "Menu", 
  "Select", 
  "Stop", 
  "Again", 
  "Undo", 
  "Cut", 
  "Copy", 
  "Paste", 
  "Find", 
  "Mute", 
  "VolumeUp", 
  "VolumeDown", 
  "LockingCapsLock", 
  "LockingNumLock", 
  "LockingScrollLock", 
  "Comma", 
  "Equal Sign", 
  "International1", 
  "International2", 
  "International3", 
  "International4", 
  "International5", 
  "International6", 
  "International7", 
  "International8", 
  "International9", 
  "LANG1", 
  "LANG2", 
  "LANG3", 
  "LANG4", 
  "LANG5", 
  "LANG6", 
  "LANG7", 
  "LANG8", 
  "LANG9", 
  "Alternate Erase", 
  "SysReq/Attention", 
  "Cancel", 
  "Clear", 
  "Prior", 
  "Return", 
  "Separator", 
  "Out", 
  "Oper", 
  "Clear/Again", 
  "CrSel/Props", 
  "ExSel", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "LeftControl", 
  "LeftShift", 
  "LeftAlt", 
  "LeftGUI", 
  "RightControl", 
  "RightShift", 
  "RightAlt", 
  "RightGUI", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  "", 
  ""
};




public void save_setting_to_file(int file_id) {
  XML save_file=new XML("key_mapping");
  if (control_panel.enable_kbd_checkbox.getState(0)) save_file.setInt("Default_en_KBD", 1);
  else save_file.setInt("Default_ENB_KBD", 0);
  for (int i=0;i<8;i++) {
    One_channel channel_ptr = channels[i];
    XML channel_child = save_file.addChild("channel");
    channel_child.setContent("Channel "+i);
    channel_child.setInt("id", i);
    int steps =  channel_ptr.div_now;
    channel_child.setInt("steps", steps);
    channel_child.setInt("repeat", channel_ptr.repeat_mode);
    channel_child.setInt("hyst", channel_ptr.hysteresis);
    channel_child.setInt("joy", channel_ptr.joy_stick);
    for (int j=0;j<steps;j++) {
      XML key_child = channel_child.addChild("key");
      key_child.setContent("Key "+j);
      key_child.setInt("modifier", channel_ptr.keycode[j*2]);
      key_child.setInt("keycode", channel_ptr.keycode[j*2+1]);
    }
  }
  saveXML(save_file, "key_mapping"+file_id+".xml");
}


public void load_setting_from_file(int file_id) {
  XML load_file = loadXML("key_mapping"+file_id+".xml");

  if (load_file == null) {
    println("File not exist");
    return;
  }

  if (load_file.getInt("Default_en_KBD")>0) control_panel.enable_kbd_checkbox.activate(0);
  else control_panel.enable_kbd_checkbox.deactivate(0);

  XML[] channels_XML = load_file.getChildren("channel");

  for (int i = 0; i < channels.length; i++) {
    int id = channels_XML[i].getInt("id");
    if (id<8) {
      One_channel channel_ptr = channels[id];
      XML[] keys = channels_XML[i].getChildren("key");
      for (int j = 0; j < keys.length; j++) {
        //int key_id=keys[j].getInt();
        int key_id=j;
        if (key_id<32) {
          channel_ptr.keycode[j*2]=(byte)keys[j].getInt("modifier");
          channel_ptr.keycode[j*2+1]=(byte)keys[j].getInt("keycode");
        }
      }
      int steps=channels_XML[i].getInt("steps");
      channel_ptr.repeat_mode=channels_XML[i].getInt("repeat");
      channel_ptr.hysteresis=channels_XML[i].getInt("hyst");
      channel_ptr.joy_stick=channels_XML[i].getInt("joy");
      channel_ptr.update_div(steps, true);
    }
  }
}


int Sensor0, Sensor1, Sensor2, Sensor3, Sensor4, Sensor5, Sensor6, Sensor7;

  static public void main(String[] passedArgs) {
    String[] appletArgs = new String[] { "MakeSenseConnector" };
    if (passedArgs != null) {
      PApplet.main(concat(appletArgs, passedArgs));
    } else {
      PApplet.main(appletArgs);
    }
	
  }
  
 
  
  
}
