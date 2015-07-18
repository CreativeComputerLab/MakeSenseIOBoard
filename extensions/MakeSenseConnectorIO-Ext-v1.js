(function(ext) {
 	console.log("Loading Make!Sense USB extension version 1.0...");
	var dataURL = 'ws://127.0.0.1:8282/';
    var ws = null;	
	var channel = [0,0,0,0,0,0,0,0];

	var wsOnmessage = function (evt) {
      var message = evt.data.split(":");
	  
	  if (message[0].substring(0,2) == "ch") { 
		var chNum = message[0].replace(/[^0-9]/g,'');
		//console.log(channel);
		channel[chNum] = message[1];
	  }
	};
	
    // Cleanup function when the extension is unloaded
    ext._shutdown = function() { if (ws.socket.connected) {ws.socket.disconnect();}};

    // Status reporting code
    // Use this to report missing hardware, plugin or unsupported browser
    ext._getStatus = function()  {
		if (ws.readyState == 1) {
			return {status: 2, msg: 'Connected'};
		}else	{return {status: 1, msg: 'Not connected'};}
	};
	
	ext.GetData = function (ch) {
		var chNum = ch.replace(/[^0-9]/g,'');
		return channel[chNum];
	};
	
	ext.SetData = function (ch, onOff) {
		var chNum = ch.replace(/[^0-9]/g,'');
		var level = 'L';
		if (onOff === 'on')
			level = 'H';	
		ws.send('ch:' + chNum + ':' + level);
	};	   
	   
    //Connection
	ext.Connect = function () {
		/* first time connecton */
		if (ws === null) {
			console.log("connecting to server");
			ws = new WebSocket(dataURL);
			ws.onmessage = wsOnmessage;
			console.log(ws);
		} else if (!(ws.readyState == 1)) {
			console.log("connecting to server");
			ws = new WebSocket(dataURL);
			ws.onmessage = wsOnmessage;
			console.log(ws);
		} else { console.log ("Connect: socket already connected");}
	};
	
	ext.Disconnect = function (callback) {
		if (!(ws === null)) {		
			if (ws.readyState == 1) {
				console.log("disconnecting from server");
				ws.close();
			} else { console.log ("Disconnect: socket already disconnected");}
		}
	};	   

    // Block and block menu descriptions
    var descriptor = {
        blocks: [
            // Block type, block name, function name, param1 default value, param2 default value
            ['r', 'get data from %m.anaChannels', 'GetData', "Channel 0"],
            [' ', 'turn %m.outChannels  %m.onOff', 'SetData', "Channel 4", "off"],
			//Connection			
			[' ', 'connect to Make!Sense', 'Connect'],
			[' ', 'disconnect from Make!Sense', 'Disconnect']
        ],
		
		menus: {
			anaChannels: ['Channel 0', 'Channel 1', 'Channel 2', 'Channel 3'],
			outChannels: ['Channel 4', 'Channel 5', 'Channel 6', 'Channel 7'],
			onOff: ['on', 'off']
		}
    };

    // Register the extension
    ScratchExtensions.register('Make!Sense', descriptor, ext);
})({});
