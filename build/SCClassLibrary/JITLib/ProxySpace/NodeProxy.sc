
NodeProxy : AbstractFunction {

	var <server, <group, <bus;
	var <nodeMap, <synthDefs, <parents;
	var <>clock;
	
	*new { arg server;
		^super.newCopyArgs(server).ninit(server)	
	}
	*audio { arg server, numChannels=2;
		^this.new(server).allocBus(\audio, numChannels);
	}
	*control { arg server, numChannels=1;
		^this.new(server).allocBus(\control, numChannels);
	}
	ninit { arg  argServer, argNumChannels;
		server = argServer ? Server.local;
		this.clear;
	}
	
	*initClass {
		//supposes it is on server already. revisit
		SynthDef("proxyOut-linkDefAr", { arg i_busOut=0, i_busIn=16; 
			Out.ar(i_busOut, InFeedback.ar(i_busIn, 1)) 
		}).writeDefFile;
	}
	
	clear {
		nodeMap = ProxyNodeMap.new;
		synthDefs = Array.new;
		this.initParents;
		bus = nil;
		//not yet implemented: if(bus.notNil, { bus.free });
	}

	initParents {
		parents = IdentitySet.new;
	}
	
	allocBus { arg rate=\audio, numChannels=2;
		bus = Bus.perform(rate, server, numChannels);
	}
	
	fadeTime_ { arg t;
		this.set(\synthFadeTime, t);
	}
	
	// playing and access
	
	ar { arg numChannels;
		if(bus.isNil, { this.allocBus(\audio, 2) });
		^this.getOutput(numChannels)
	}
	
	kr { arg numChannels;
		if(bus.isNil, { this.allocBus(\control, 1) });
		^this.getOutput(numChannels)
	}
	
	value { ^if(this.rate == 'audio', {this.ar}, {this.kr})  }
	
	
	play { arg outBus=0, nChan;
		var playGroup, msg;
		if(bus.isNil, {this.allocBus(\audio, nChan ? 2) });
		this.wakeUpParents;
		
		msg = List.new;
		playGroup = Group.newMsg(msg, server);
		nChan = nChan ? this.numChannels;
		nChan = nChan.min(this.numChannels);
		
		nChan.do({ arg i;
			Synth.newMsg(msg, "proxyOut-linkDefAr", [\i_busOut, outBus+i, \i_busIn, bus.index+i], playGroup, \addToTail);
		});
		server.listSendBundle(nil, msg);
		^playGroup
	}
	
	send { arg extraArgs;
			this.sendToServer(false, 0.0, extraArgs);
	}
	
	record { arg path, headerFormat="aiff", sampleFormat="int16";
		var rec;
		rec = RecNodeProxy.newFrom(this);
		Routine({
			1.0.wait;//must wait.
			rec.record(path, headerFormat, sampleFormat);
		}).play;
		^rec
	}
	
		
	load {
		if(server.serverRunning, {
			parents.do({ arg item; item.load });
			this.updateSynthDefs;
		}, { "server not running".inform });
	}
	
	rate { ^bus.tryPerform(\rate) }
	
	
	numChannels {
		^bus.tryPerform(\numChannels);
	}
	
	isPlaying { 
		^(group.notNil and: {group.isPlaying})
	}
	
	
	
	// setting the source to anything that returns a valid ugen input
	
	add { arg obj;
		this.setObj(obj, true, false, true)
	}
	source_ { arg obj; 
		this.setObj(obj, true) 
	}
	
	setObj { arg obj, send=false, freeLast=true, add=false, onCompletion, latency=0.5; 		var def, ok, writeOK;
			
			ok = this.initFor(obj);
			
			if(ok, {
				if(add.not, { 
					synthDefs = Array.new; 
					this.initParents 
				});
				
				def = obj.asProxySynthDef(this);
				synthDefs = synthDefs.add(def);
				def.writeDefFile;
				server.sendMsg("/d_recv", def.asBytes);
				
				
				if(send, { this.sendToServer(freeLast, latency, onCompletion) });
				
			}, { "rate/numChannels must match".inform })
			
	}
	
	
	
	

	
	
	////////////behave like my group////////////
	
	free {
		if(this.isPlaying, { group.free });
	}
	
	
	set { arg ... args;
		nodeMap.performList(\set, args);
		if(this.isPlaying, { group.performList(\set, args) });
	}
	
	setn { arg ... args;
		nodeMap.performList(\setn, args);
		if(this.isPlaying, { group.performList(\setn, args) });
	}
	
	//map to a control proxy
	map { arg key, proxy; //...args doesn't work. why?
		var args;
		if(proxy.rate === 'control', { 
			args = [key, proxy];
			nodeMap.performList(\map, args);
			if(this.isPlaying, { nodeMap.send(group) })
		}, {
			"can only map to control bus".inform
		})
	}
	
	unset { arg ... args;
		nodeMap.performList(\unset, args);
	}
	
	unmap { arg ... args;
		nodeMap.performList(\unmap, args);
		if(this.isPlaying, { nodeMap.send(group) });
	}
	release {
		if(this.isPlaying, { group.set(\synthGate, 0.0) });
	}
	run { arg flag=true;
		if(this.isPlaying, { group.run(flag) });
	}
	
	/////////////////////////////////////////////
	
	
			
	// server communications, updating
	
	sendToServer { arg freeLast=true, latency=0.3, extraArgs, onCompletion;
		var msg, resp;
		if( synthDefs.isEmpty.not and: { server.serverRunning }, {
				msg = List.new;
				this.sendSynthMsg(msg, freeLast, extraArgs);
				
				if(latency.notNil, {
						SystemClock.sched(latency, {
							this.schedSendOSC(msg, onCompletion); 
						});
					
					}, {
						//doesn't work when several defs are sent at once.
						resp = OSCresponder(server.addr, '/done', {
							this.schedSendOSC(msg, onCompletion);
							resp.remove;
						}).add
				});
				
		});
	}
	
	schedSendOSC { arg msg, onCompletion;
					if(clock.notNil, {
						clock.sched(0, { 
							server.listSendBundle(nil, msg); 
							onCompletion.value(this) 
						})
					}, {
						server.listSendBundle(nil, msg); 
						onCompletion.value(this)
					})
	}
	
	updateSynthDefs {
		if(synthDefs.isEmpty.not, { 
			synthDefs.do({ arg synthDef;
			/*
			if(server.isLocal, {//maybe direct send would be better? server reboot would destroy them?
				server.sendSynthDef(synthDef.name)
				//server.loadSynthDef(synthDef.name)
			}, {
				server.sendSynthDef(synthDef.name)
			}); 
			*/
			//server.sendSynthDef(synthDef.name);
			//server.loadSynthDef(synthDef.name);
			server.sendMsg("/d_recv", synthDef.asBytes);
			});
				
		});
	}
	
	startGroupMsg { arg msg;
					group = Group.newMsg(msg, server, \addToHead);
					group.prIsPlaying(true);
				
	}
	
	sendSynthMsg { arg msg, freeLast=true, extraArgs;
	
				if(this.isPlaying.not, {
					this.startGroupMsg(msg);
				}, {
					//release current synth
					if(freeLast, {
						group.addMsg(msg, "/n_set", [\synthGate, 0.0])
					});
				});
			synthDefs.do({ arg synthDef;
				var synth;
				synth = Synth.newMsg(msg, synthDef.name, extraArgs, group); 
				nodeMap.updateMsg(msg, synth);
			});
		
	
	}
	
		
	////// private /////
	
	initFor { arg obj;
		var rate, array, numChannels;
			if(obj.notNil, {
				//for speed
				if(obj.isKindOf(NodeProxy), {
					rate = obj.rate;
					numChannels = obj.numChannels ? 1;
				},{
					array = obj.value.asArray;
					rate = array.rate;
					numChannels = array.size;
				});
				if(rate !== 'audio', { rate = 'control' });//see later for scalars
				if(bus.isNil, {
					
					bus = Bus.perform(rate, server, numChannels);
					nodeMap = ProxyNodeMap.new;
					
					^true
				}, {
					^(bus.rate === rate) && (numChannels <= bus.numChannels)
				});
			});
			^false
		
	}

	getOutput { arg numChannels;
		var out, n;
		this.addToBuildSynthDef;
		this.wakeUpParents;
		n = bus.numChannels;
		out = if(this.rate === 'audio', 
				{ InFeedback.ar( bus.index, n) },
				{ In.kr( bus.index, n) }
			);
		//test that
		/*
		if(numChannels != n, {
			out = NumChannels.ar(out, numChannels, false)
		}); 
		*/
		^out
		
	}
	
	addToBuildSynthDef {
		var parentProxy;
		parentProxy = UGen.buildSynthDef.tryPerform(\proxy);
		if(parentProxy.notNil && (parentProxy !== this), { parentProxy.parents.add(this) });
	}
	
	wakeUp { 
		
		if(this.isPlaying.not, { this.sendToServer(true, 0) });//no need to wait, def is on server
	
	}
	
	wakeUpParents {
		if(this.isPlaying.not, {
			parents.do({ arg item; item.wakeUpParents });
			this.wakeUp;
		});
	}
	
		
	
	
}