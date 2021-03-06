import org.jgroups.Address;
import org.jgroups.JChannel;

import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;

import java.util.ArrayList;
import java.util.Date;
//import java.util.HashMap;
import java.util.Vector;
//import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;

public class SimpleMessagePassing extends ReceiverAdapter {
	 
	@SuppressWarnings("resource")
	private void start() throws Exception {
		//channel=new JChannel("tcp.xml").setReceiver(this);
		//channel=new JChannel("simpleMessageJgroupConfig.xml").setReceiver(this);
		channel=new JChannel().setReceiver(this);
        channel.connect("Cluster"); 
        
        localComputation();
        channel.close();
    }
	
	public void viewAccepted(View new_view) {
	//	System.out.println("** view: " + new_view);
	} 
									
	public void receive(Message msg) {
		
		try {
			Packet receivedPacket = (Packet) (msg.getObject());
			switch(receivedPacket.getMessageType()) {
				case START_LATENCY: 
					//localStatisticsCollector = new LocalStatisticsCollector();
					localSetup();
					state = LocalState.GET_LATENCY;
					break;
			 	case PING_LATENCY: 
		 			Packet pong = new Packet(MessageType.PONG_LATENCY,receivedPacket.getTime()); 
		 			int receivedFrom = receivedPacket.getIndexFrom();
		 			channel.send(SimpleMessageUtilities.getOobMessage(members.get(receivedFrom), pong));
			 		break;
			 	case PONG_LATENCY:
			 		if(state == LocalState.EXECUTE_LATENCY_RUN  || state == LocalState.GET_LATENCY) {
			 			Duration RTT = Duration.between(receivedPacket.getTime(), Instant.now()); 
			 			try {
			 				long durationMicrosec = Math.round(RTT.toNanos()/(2*1000.0));
				 			synchronized(localStatisticsCollector) {
				 				localStatisticsCollector.updateLocalRTTs(durationMicrosec);
				 			} 
			 			} catch(Exception e) {
			 				e.printStackTrace();
			 			}
			 		}
			 		break;
			 	case COLLECT_LATENCY: 
			 		LocalStatisticsCollector receivedLocalStatistics = receivedPacket.getLocalStatisticsCollector();
			 		synchronized(localStatisticsCollector) {
			 			localStatisticsCollector.pushLocalStatistics(receivedLocalStatistics);
				 		if(localStatisticsCollector.numReceivedEqualTo(numWorkers)) { 
				 			
				 			localStatisticsCollector.printStatistics();
				 			
				 			if(state == LocalState.SETUP_LATENCY_RUN) {
				 				localStatisticsCollector.logStatistics(outputLog);
				 			}
				 			else state = LocalState.IDLE;
				 		}
			 		}
			 		break;
				case NORMAL_RECEIVE:
					if(state==LocalState.EXECUTE_NORMAL_RUN ) {
						Timestamp t = receivedPacket.getLocalTimestamp();						
						synchronized(localClock) {							
							//localClock.timestampReceiveEvent(t);//removing this and calling new HLCtimestamping method that takes current instant too
							Instant currentTime= Instant.now();
							localClock.timestampReceiveEventHLC(t,currentTime);
							LocalEvent newLocalEvent=new LocalEvent(EventType.RECEIVE_MESSAGE,localClock,currentTime,x);
							MsgTrace newMsgTrace = new MsgTrace(receivedPacket.getIndexFrom(),this.myIndexOfTheGroup,receivedPacket.getTime(),currentTime,receivedPacket.getLocalTimestamp(),localClock);
							//newMsgTrace.Print();
							localTraceCollector.pushLocalTraceReceive(newLocalEvent,newMsgTrace);
							/*
							System.out.println("Received: ");
							t.print();
							System.out.println("Before: ");
							localClock.print();
							localClock.timestampReceiveEvent(t);
							System.out.println("After receive");
							localClock.print(); */
							//System.out.println("elapsedTimeSinceReport in nanos:"+elapsedTimeSinceReport.toNanos());
							if(Duration.between(lastReportTime, currentTime).toNanos()/1000 > parameters.globalEpsilon) //only if duration of run is not yet done
							{
								state = LocalState.REPORT_WINDOW;
							}
						}
					}
					break;
				
				case CONFIG_START:
					parameters = new RunningParameters(receivedPacket.getRunningParameter());
					if(parameters.ntpTypeString.equals("amazon"))
						state = LocalState.GET_AMAZON_NTP_RUN;
					else {
						state = LocalState.GET_INTERNET_NTP_RUN;
					}
					break;
				case CONFIG_STOP:
					state = LocalState.STOP;
					break;
				case CONFIG_FINISH: 
					int pktFrom=receivedPacket.getIndexFrom();
					System.out.println("Received CONFIG_FINISH from "+pktFrom);
					ConfigFinishCounter++;
					boolean missingWindow=false;
					synchronized(leaderTraceCollector) {
						missingWindow=windowToBufferToTraceCollector(receivedPacket,pktFrom);
						//processing leaderTraceCollector- if its completely filled - i.e.processing Ahead and Behind LeaderTraceCollectors as well
						if ( leaderTraceCollector.hasReceivedFromAllMembers() ) {
							System.out.println("Collected trace from all members.");							
							if (ConfigFinishCounter == parameters.numberOfMembers) {//if final report from all processes were received						
								//possibility is either the ahead collector has traces for a epsilon window or is empty(duration of run is smaller than epsilon) 
								//so process ahead collector --behind collector will never have info that ahead collector does not know
								leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollector);
								leaderTraceCollectorAhead.solveConstraints(parameters.globalEpsilon,parameters.numberOfMembers,outputLog);
								
							} else {
								if (leaderTraceCollectorAhead.isEmpty() && leaderTraceCollectorBehind.isEmpty()) {
									leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollector);
								} //if ahead collector has one epsilon window trace and behind collector is empty
								else if(!leaderTraceCollectorAhead.isEmpty() && leaderTraceCollectorBehind.isEmpty()) {
									leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollector);//now ahead collector is filled for 2*epsilon traces
									leaderTraceCollectorBehind.appendLeaderTrace(leaderTraceCollector);//now behind has one epsilon window traces
									//process ahead collector
									leaderTraceCollectorAhead.solveConstraints(parameters.globalEpsilon,parameters.numberOfMembers,outputLog);
									//make behind collector as the new ahead collector
									leaderTraceCollectorAhead = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
									leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollectorBehind);
									//clear and create a new behind collector
									leaderTraceCollectorBehind = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
								}
							}							
							//reset current-window-collector
							leaderTraceCollector = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());	
						}
						//Wrapping up if all processes have reported all windows i.e.sent their final reports
						//all windows are received only if there are no missing windows! not just receive config-finish
						if((ConfigFinishCounter == parameters.numberOfMembers-1)&&(!missingWindow)) {
							leaderBuffer.printLeaderBuffer("LeaderBuffer.txt");						
							//should set this state only after all reports are processed.
							state = LocalState.IDLE;
							System.out.println("Clearing buffers.");
							leaderBuffer.clear();
							lastProcessed.clear();
						} else {
							System.out.println("Not setting to idle yet.");
						}
					}
					break;
				case WINDOW_REPORT: 
					//System.out.print("Received WINDOW_REPORT ");
					int reportFrom = receivedPacket.getIndexFrom();
					System.out.println("\nReceived a window report from "+reportFrom+" with timestamp l:"+receivedPacket.getLocalTimestamp().getL()+" at pt:"+Instant.now());
					outputLog.write("\nReceived a window report from "+reportFrom+" with timestamp <l:"+receivedPacket.getLocalTimestamp().getL()+",c:"+receivedPacket.getLocalTimestamp().getC()+"> at pt:"+Instant.now());
					synchronized(leaderTraceCollector) {
						missingWindow=windowToBufferToTraceCollector(receivedPacket,reportFrom);
						//System.out.println("Printing lower bounds for LeaderTraceCollector:");
						//leaderTraceCollector.printLowerBounds();
						if (leaderTraceCollector.hasReceivedFromAllMembers()) {
							//System.out.println("Collected trace from all members.");
							//First 2* epsilon window
							if (leaderTraceCollectorAhead.isEmpty() && leaderTraceCollectorBehind.isEmpty()) {
								leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollector);
							} //if ahead collector has one epsilon window trace and behind collector is empty
							else if(!leaderTraceCollectorAhead.isEmpty() && leaderTraceCollectorBehind.isEmpty()) {
								leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollector);//now ahead collector is filled for 2*epsilon traces
								leaderTraceCollectorBehind.appendLeaderTrace(leaderTraceCollector);//now behind has one epsilon window traces
								//System.out.println("Printing lower bounds for Ahead Collector:");
								//leaderTraceCollectorAhead.printLowerBounds();
								//process ahead collector
								leaderTraceCollectorAhead.solveConstraints(parameters.globalEpsilon,parameters.numberOfMembers,outputLog);
								//make behind collector as the new ahead collector
								leaderTraceCollectorAhead = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
								leaderTraceCollectorAhead.appendLeaderTrace(leaderTraceCollectorBehind);
								//clear and create a new behind collector
								leaderTraceCollectorBehind = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
							}
							//reset current-window-collector
							leaderTraceCollector = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
						}
						//Wrapping up if all processes have reported all windows i.e.sent their final reports
						//all windows are received only if there are no missing windows!! not just receive config-finish
						if((ConfigFinishCounter == parameters.numberOfMembers-1)&&(!missingWindow)) {
							leaderBuffer.printLeaderBuffer("LeaderBuffer.txt");						
							//should set this state only after all reports are processed.
							state = LocalState.IDLE;
							System.out.println("Clearing buffers.");
							leaderBuffer.clear();
							lastProcessed.clear();
						} else {
							System.out.println("Not setting to idle yet.");
						}
					}
					break;
				case REQUEST_INTERNET_NTP: 
					state = LocalState.GET_INTERNET_NTP;
					break;
				
				case REQUEST_AMAZON_NTP: 
					state = LocalState.GET_AMAZON_NTP;
					break;
				case REPLY_NTP:
					double localNtpOffset = receivedPacket.getNtpOffset();
					globalNtpOffset.add(localNtpOffset);
					
					if(globalNtpOffset.size()==numWorkers) {
						
						reportOffsetInfo();	
						if(state==LocalState.GET_AMAZON_NTP_RUN || state==LocalState.GET_INTERNET_NTP_RUN ) 
							logOffsetInfo();//writing offsets info to file	
						if(state==LocalState.GET_AMAZON_NTP_RUN || state==LocalState.GET_INTERNET_NTP_RUN ) { 
							state = LocalState.SETUP_LATENCY_RUN;
						}
						else {
							state = LocalState.IDLE;
						}
					}
					break;
				case PING:
					System.out.println("Received ping.");
					break;
				case IGNORE: 
					System.out.println("Warning: received IGNORE message");
				default:
					
				break;
			}
		} catch(Exception e) {
		  	e.printStackTrace();
		}
	}

	private int indexOfMyAddress(java.util.List<org.jgroups.Address> members)  {
	    int i =0;
	    org.jgroups.Address myAddress = (org.jgroups.Address) channel.getAddress();
		for(org.jgroups.Address addr : members) {
			//System.out.println(addr + " vs. " + myAddress);
			if(addr.toString()!=myAddress.toString()) {
				i++;
			}
			else {
				//System.out.println(members.get(i) + " vs. " + myAddress);
				return i;
			}
		}
		return i;
	}

	
	private void reportOffsetInfo() {
		System.out.println("\n -------- listing offsets -------- ");
		for(double d : globalNtpOffset) {
			System.out.print(d + " ");
		}
		System.out.println("\nAverage Offset is : " + SimpleMessageUtilities.average(globalNtpOffset)  + " ms");
		System.out.println("Max Offset is : " + SimpleMessageUtilities.max(globalNtpOffset) + " ms");
		System.out.println("---------------- ");
	}
	
	private void logOffsetInfo() throws IOException {
		outputLog.write("-------- listing offsets -------- ");
		
		outputLog.write(System.getProperty( "line.separator" ));
		for(double d : globalNtpOffset) {
			outputLog.write(d + " ");
		}
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Average Offset is : " + SimpleMessageUtilities.average(globalNtpOffset) + " ms");
		outputLog.write(System.getProperty( "line.separator" ));
	    	outputLog.write("Max Offset is : " + SimpleMessageUtilities.max(globalNtpOffset) + " ms");
	    	outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("---------------- ");
		outputLog.write(System.getProperty( "line.separator" ));
	}
	
	
	private void localSetup(){
		this.members = channel.getView().getMembers();  
		this.myIndexOfTheGroup = indexOfMyAddress(members); 
		this.numWorkers = this.members.size()-1;
		this.localClock = new Timestamp(TimestampType.NO_TIMESTAMP,this.members.size(),myIndexOfTheGroup,1);
		localStatisticsCollector = new LocalStatisticsCollector();
		this.x=false;//to start with the variable is false
		this.trueUntil=Instant.now();
	}
	private void leaderSetup() {
		localSetup();
	
		this.localClock = new Timestamp(parameters.timestampType,this.members.size(), myIndexOfTheGroup,parameters.globalEpsilon);
		parameters.setRandom(myIndexOfTheGroup);
		leaderTraceCollector = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
		leaderTraceCollectorAhead = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
		leaderTraceCollectorBehind = new LeaderTraceCollector(parameters.numberOfMembers,parameters.startTime.toInstant());
	}
	private void nonLeaderSetup() {
		localSetup();
		this.localClock = new Timestamp(parameters.timestampType,this.members.size(), myIndexOfTheGroup,parameters.globalEpsilon);
		parameters.setRandom(myIndexOfTheGroup);
		localTraceCollector = new LocalTraceCollector(parameters.numberOfMembers, members.get(0));
	}
	private void printInstruction() {
		System.out.println("usage: start "
						+ "[int duration:s] "
						+ "[int timeunit:mu-s] "
						+ "[unicast_probability:0-1] "
						//+ "[long hvc_collecting_peroid:ms] "
						+ "[long local variable true interval length: mu-s] "
						+ "[long epsilon:mu-s] "
						+ "[string uniform || zipf={double skew}]" 
						+ "[string query: no || yes={epsilon_start:epsilon_interval:epsilon_stop} mu-s"
						+ "[string causality_clock: {hvc,vc,hlc, stat_hvc, no_clock}" 
						+ "[string ntp_type: {amazon, internet}"
						+ "[beta probability: 0-1]");
		System.out.println("usage2: get num_nodes || ntp_internet (ms) || ntp_amazon (ms) || latency (mu-s)");
		System.out.println("usage3: exit");
	}
	private boolean correctFormat(String[] cmd) {
		boolean lengthEqual11 = cmd.length == 11;
		if(!lengthEqual11) { 
			System.out.println("Incorrect number of parameters.");
			return false;
		}
		boolean firstisint = SimpleMessageUtilities.isInteger(cmd[1]);
		boolean secondisint = SimpleMessageUtilities.isInteger(cmd[2]);
		boolean thirdisreal = SimpleMessageUtilities.isNumeric(cmd[3]);
		boolean fourthislong = SimpleMessageUtilities.isLong(cmd[4]);
		boolean fifthislong = SimpleMessageUtilities.isLong(cmd[5]);
		
		if( firstisint && secondisint && thirdisreal && fourthislong && fifthislong)  {
			double unicastProbabilityMessage = Double.parseDouble(cmd[3]);
			if(unicastProbabilityMessage <= 0 || unicastProbabilityMessage >1)  {
				System.out.println("Need unicast_probability between 0 and 1");
				return false;
			}
			double beta = Double.parseDouble(cmd[10]);
			if(beta < 0 || beta >1)  {
				System.out.println("Need beta between 0 and 1");
				return false;
			}
		}
		
		String causalityClockString = cmd[8].toLowerCase().trim();
		if(causalityClockString.equals("hvc")) {	
			
		} else if(causalityClockString.equals("vc")) {
			
		} else if(causalityClockString.equals("hlc")) {	 
			
		} else if(causalityClockString.equals("stat_hvc")) {
			
		} else if(causalityClockString.equals("no_clock")) {
			
		} else {
			return false;
		}
		
		String ntpTypeString = cmd[9].toLowerCase().trim();
		if(ntpTypeString.equals("amazon")) {	
			
		} else if(ntpTypeString.equals("internet")) {
			
		} else {
			return false;
		}
		
				
		return true;
	}
	
	private void broadcastCommand(String [] cmd)  throws Exception {
		double unicastProbabilityMessage = Double.parseDouble(cmd[3]);
		int durationMessage = (Integer.parseInt(cmd[1]))*1000;
		int timeunitMessage = Integer.parseInt(cmd[2]);
		Date startTimeMessage = Date.from(Instant.now().plusSeconds(10));
		//long period = Long.parseLong(cmd[4]);
		long localVarTrueIntervalLen = Long.parseLong(cmd[4]);
		long epsilon = Long.parseLong(cmd[5]);
		String destinationDistributionString = cmd[6].toLowerCase().trim();
		String queryString = cmd[7].toLowerCase().trim();
		String causalityClockString = cmd[8].toLowerCase().trim();
		String ntpTypeString = cmd[9].toLowerCase().trim();
		double beta = Double.parseDouble(cmd[10]);
		
		int numberOfMembers = channel.getView().getMembers().size();
		//need seed to be less than 48 bits 
		long initialRandomSeed =  Instant.now().toEpochMilli()%1000003; 
		Packet packet = new Packet(MessageType.CONFIG_START,
							new RunningParameters( 
							    numberOfMembers,  
							    initialRandomSeed,
								unicastProbabilityMessage,
								timeunitMessage,
								durationMessage,
								startTimeMessage,
								localVarTrueIntervalLen,
								epsilon,
								destinationDistributionString,
								queryString,
								causalityClockString,
								ntpTypeString,
								beta
								) 
		);
		
	    channel.send(null,packet);
	    
	    System.out.println("---------");
	    System.out.println("start messages have been sent.");
		System.out.println("With the following parameters:");
		System.out.println("StartTime = "+startTimeMessage);
		System.out.println("Duration = "+ cmd[1] + " s");
		System.out.println("Time Unit = "+cmd[2] + "mu-s");
		System.out.println("UnicastProbability = "+cmd[3]);
		//System.out.println("Period = "+cmd[4] + "ms");
		System.out.println("Local Variable True Interval length = "+cmd[4] + "mu-s");
		System.out.println("Epsilon = " + cmd[5] + "mu-s");
		System.out.println(destinationDistributionString);
		System.out.println(queryString);
		System.out.println(causalityClockString);
		System.out.println("NTP type ="+ ntpTypeString);
		System.out.println("Beta - probability at which local variable becomes true ="+ beta);
		System.out.println("---------");
		
		 
		this.numWorkers = channel.getView().getMembers().size()-1;
		
		outputLog.write("Number of Worker Nodes = " + numWorkers);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("StartTime = "+startTimeMessage);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Duration = "+ cmd[1] + " s");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Time Unit = "+cmd[2] + "mu-s");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("UnicastProbability = "+cmd[3]);		
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Local Variable X - True Interval Length = "+cmd[4] + "mu-s");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Epsilon = " + cmd[5] + "mu-s");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write(destinationDistributionString);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write(queryString);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write(causalityClockString);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("NTP Type "+ ntpTypeString);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("Beta = "+cmd[10]);		
		outputLog.write(System.getProperty( "line.separator" ));
	}
	
	private String cmdToFileName(String [] cmd) {
		
		String filename = "run";
		String destinationDistributionString = cmd[6].toLowerCase().trim(); 
		String causalityClockString = cmd[8].toLowerCase().trim();
 
		filename += "-"+causalityClockString;
		filename += "-" + destinationDistributionString;
		filename += "-eps=" + cmd[5] +"mus";
		filename += "-alpha=" + cmd[3];
		filename += "-timeUnit="+ cmd[2]+"mus";
		filename += "-duration="+cmd[1]+"s";
		filename += "-beta="+cmd[10];
		return filename;
		
	}
	private void waitUntilReceivedAllLocalStates(LocalState s) throws Exception {
		while(state != s) {
			Thread.sleep(1000);
			System.out.print(".");
		} 
	}
	
	private void runLeaderRoutine()  {
		try {
			while(true) { 
				state = LocalState.IDLE;
				Thread.sleep(100);  
				printInstruction();
				System.out.print("> ");
				BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
				String line =in.readLine().toLowerCase();
				if(line.startsWith("start")) {
					String[] command = line.split(" ");
					if(!correctFormat(command)) {
						//printInstruction();
						continue;
					}
					outputFilename =  cmdToFileName(command);//create file name using parameters in input command
					outputLog = new FileWriter("./" + outputFilename+".txt", false);
					//leaderBuffer = new ConcurrentHashMap<String,LocalTraceCollector>();
					leaderBuffer = new LeaderBuffer(channel.getView().getMembers().size());
					lastProcessed = new ArrayList<>();
					for(int i = 0; i< channel.getView().getMembers().size();i++) {
						lastProcessed.add(new Timestamp());
					}
					ConfigFinishCounter = 0;
					globalNtpOffset = new Vector<>();
					
					broadcastCommand(command); //send parameters to everybody//all instances receive the parameters and configure themselves
					while(state!=LocalState.SETUP_LATENCY_RUN) {
						System.out.print(".");
						Thread.sleep(1000);
					}
					System.out.println("leader setup");
					leaderSetup();//all trace collectors should get initialized here
					//variable to remember at what time all members started their normal run
					Instant initL = parameters.startTime.toInstant().plusMillis(parameters.duration+5*1000);  
					//SimpleMessageUtilities.waitUntil(Date.from(initL));
					//commonStartTime  = Instant.now();
					commonStartTime = initL;
					waitUntilReceivedAllLocalStates(LocalState.IDLE); 
					
					outputLog.close();
					System.out.println("The execution has been completed.");
					
				}
				else if(line.startsWith("get")) {
					
					//System.out.println("usage2: info num_nodes || ntp_internet || ntp_amazon || ");
					String[] command = line.split(" ");
					boolean length2= command.length == 2;
					if(!length2) { 
						// printInstruction();
						 continue;
					}
					if(command[1].startsWith("num_node")) {
						System.out.println("Number of nodes including leader is " + channel.getView().getMembers().size());
						continue;
					}
					this.numWorkers  = channel.getView().getMembers().size()-1;
					System.out.println("Number of worker nodes is " + numWorkers);
					globalNtpOffset = new Vector<>();
					Packet packet;  
					if(command[1].startsWith("ntp_int")) {
						packet = new Packet(MessageType.REQUEST_INTERNET_NTP);
						//channel.send(SimpleMessageUtilities.getOobMessage(null, packet));
						channel.send(null,packet);
						state = LocalState.GET_INTERNET_NTP;
					
					}
					else if(command[1].startsWith("ntp_ama")) {
						packet = new Packet(MessageType.REQUEST_AMAZON_NTP);
						channel.send(null,packet);
						state = LocalState.GET_AMAZON_NTP;
					}
					else if (command[1].startsWith("laten")) {
						packet = new Packet(MessageType.START_LATENCY);
						channel.send(null,packet);
						state = LocalState.GET_LATENCY;
					}
					else {
						//printInstruction();
						continue;
					} 
					while(state!=LocalState.IDLE) {
						Thread.sleep(10); 
					}
				}
				else if (line.startsWith("quit") || line.startsWith("exit")) {
					Packet packet = new Packet(MessageType.CONFIG_STOP);
					channel.send(null,packet);
					return;
				}
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	private void nonLeaderRoutine() {
		 //the following loop is for non-leaders
		try {
			state = LocalState.IDLE;
			Instant initTime = Instant.now();
			Duration elapsedTime; //= initTime;
			lastReportTime= Instant.now();
			Duration elapsedTimeSinceReport;
			Instant initL = initTime;//variable used to start normal run at all instances at the same time
			//Instant lastL = initTime;
			boolean sendMessage,xBecomesTrue;
			windowStart=true;
			//local state may be changed upon receiving a message
			while(true) {
				switch(state) {
				case IDLE:  
					Thread.sleep(1000); 
					System.out.print(".");
				//	this.members = channel.getView().getMembers();  
				//	this.myIndexOfTheGroup = indexOfMyAddress(members); 
				/*	if(myIndexOfTheGroup==1) {
						Thread.sleep(5000); 
						//ping leader to let ssh connection stay alive
						 Packet pingpacket = new Packet(MessageType.PING);
						 channel.send(members.get(0),pingpacket);
					}*/
					//waiting for leader's command to run or to stop the programs.
					continue;
				case GET_INTERNET_NTP:
				case GET_INTERNET_NTP_RUN:
				case GET_AMAZON_NTP: 
				case GET_AMAZON_NTP_RUN: 
					 this.members = channel.getView().getMembers();  
					 this.myIndexOfTheGroup = indexOfMyAddress(members); 
					 double ntpOffset; 
					 Packet packetinfo;
					 if(state==LocalState.GET_AMAZON_NTP || state==LocalState.GET_AMAZON_NTP_RUN) {
						 ntpOffset = SimpleMessageUtilities.getAmazonNtpOffset();
					 } else {
						 ntpOffset = SimpleMessageUtilities.getInternetNtpOffset();
					 }
					 packetinfo = new Packet(MessageType.REPLY_NTP, ntpOffset);
					 channel.send(members.get(0),packetinfo);
					if(state==LocalState.GET_AMAZON_NTP || state==LocalState.GET_INTERNET_NTP) {
						state = LocalState.IDLE;
					} else {
						/*removing latency run*/
						//state = LocalState.SETUP_LATENCY_RUN;
						state = LocalState.SETUP_NORMAL_RUN;
					}
					continue;
				case GET_LATENCY:  
					 int numProcesses = this.numWorkers+1;
					 int pingDestination = (myIndexOfTheGroup+1)%numProcesses; 
					 while(true) {
						 if(pingDestination == 0) pingDestination = 1;
						 if(pingDestination == myIndexOfTheGroup) break;
						 int numTrials = 1000;
						 while(numTrials-->0) {
							//System.out.println("send to " +pingDestination);
						//	 SimpleMessageUtilities.busyWaitMicros(100);
							 SimpleMessageUtilities.spinWaitMicros(100);
						//	 Thread.sleep(1);
						// 	localClock.timestampLocalEventHLC();
						 	localClock.timestampLocalEvent();
						 	Packet packet = new Packet(MessageType.PING_LATENCY, Instant.now(), myIndexOfTheGroup);
						 	channel.send(SimpleMessageUtilities.getOobMessage(members.get(pingDestination), packet));
						 }
						 pingDestination = (pingDestination+1)%numProcesses;
					 }
					 Thread.sleep(1000);
					
					 localStatisticsCollector.printStatistics();
					 channel.send(members.get(0),new Packet(MessageType.COLLECT_LATENCY, localStatisticsCollector));
					 state = LocalState.IDLE;
					 continue;
				case SETUP_NORMAL_RUN:
					 nonLeaderSetup(); 
					 System.out.println("SETUP FOR NORMAL RUN READY: My index is " + Integer.toString(myIndexOfTheGroup));
					 initL = parameters.startTime.toInstant().plusMillis(parameters.duration+5*1000);
					 //lastL = initL.plusMillis(parameters.duration);
					 SimpleMessageUtilities.waitUntil(Date.from(initL));
					 initTime  = Instant.now();
					 lastReportTime  = initTime;
					 //localClock.timestampLocalEvent(initTime); 
					 localClock.timestampLocalEvent(initL); 
					 if (windowStart) {//at the very beginning- first ever window starts here
						windowStart = false;
						localTraceCollector.setStartCausalClkInEpsilonWindow(localClock);
						System.out.println("Window start l:"+localClock.getL()+" c:"+localClock.getC()+",Instant:"+Instant.now());
					 }
					 localTraceCollector.pushLocalTrace(new LocalEvent(EventType.START, localClock, initTime,x));
					 state = LocalState.EXECUTE_NORMAL_RUN;
					 continue;
				case SETUP_LATENCY_RUN:
					 nonLeaderSetup();
					 
					 System.out.println("SETUP FOR LATENCY RUN READY: My index is " + Integer.toString(myIndexOfTheGroup));
					 initL = parameters.startTime.toInstant();
					 //lastL = initL.plusMillis(parameters.duration);
					  //System.out.println(initL);
					 SimpleMessageUtilities.waitUntil(Date.from(initL)); 
					 initTime  = Instant.now();
					 localClock.timestampLocalEvent();  
					
					 state = LocalState.EXECUTE_LATENCY_RUN;
					 continue;
				case EXECUTE_NORMAL_RUN:					
					SimpleMessageUtilities.spinWaitMicros(parameters.timeUnitMicrosec);
					//SimpleMessageUtilities.spinWaitMicrosNew(parameters.timeUnitMicrosec);
				    sendMessage = parameters.nextDouble() <= parameters.unicastProbability;
				    
				    boolean tempx= this.x;
				    Instant current = Instant.now();
				    				    
				    if(current.isAfter(trueUntil)) {
				    	//true-interval period ended
				    	this.x=false;
				    	//check using beta if x will become true
					    xBecomesTrue = parameters.nextDouble() <= parameters.beta;
				    	if(xBecomesTrue) {
				    		this.x=true;
				    		this.trueUntil=current.plusNanos(parameters.localVariableTrueIntervalLength*1000);
				    	}
				    }
				    //System.out.println("X:"+this.x);
					if(sendMessage) 
					{
						int destination = parameters.nextDestination();
						if(destination == myIndexOfTheGroup) {
							synchronized(localClock) {
								localClock.timestampLocalEvent();
							}
							if(tempx!=this.x) {//if x value changed report old x value
								localTraceCollector.pushLocalTrace(new LocalEvent(EventType.LOCAL_EVENT, localClock, Instant.now(),tempx));
							} else {
								localTraceCollector.pushLocalTrace(new LocalEvent(EventType.LOCAL_EVENT, localClock, Instant.now(),this.x));
							}
						} else {
							synchronized(localClock) {
								localClock.timestampSendEvent();
								//Packet packet = new Packet(MessageType.NORMAL_RECEIVE,localClock);
								Packet packet = new Packet(MessageType.NORMAL_RECEIVE,localClock,Instant.now(), myIndexOfTheGroup);
								channel.send(SimpleMessageUtilities.getOobMessage(members.get(destination), packet));
							}
							if(tempx!=this.x) {//if x value changed report old x value
								localTraceCollector.pushLocalTrace(new LocalEvent(EventType.SEND_MESSAGE, localClock, Instant.now(),tempx));
							} else {
								localTraceCollector.pushLocalTrace(new LocalEvent(EventType.SEND_MESSAGE, localClock, Instant.now(),this.x));
							}
						//	System.out.println("Send to : " +destination);
						//	localClock.print();
						}
					} else {//if you did not send a message and
						//if x value changed at the current instant-- create a local event
						if(tempx!=this.x) {
							synchronized(localClock) {
								localClock.timestampLocalEvent();
							}
							localTraceCollector.pushLocalTrace(new LocalEvent(EventType.LOCAL_EVENT, localClock, Instant.now(),this.x));
						}
					}
					
					Instant currInstant =Instant.now();
					elapsedTime = Duration.between(initTime, currInstant);
					//System.out.println("currInstant:"+currInstant+", elapsedTime in nanos:"+elapsedTime.getNano());
					elapsedTimeSinceReport = Duration.between(lastReportTime, Instant.now());
					//System.out.println("elapsedTimeSinceReport in nanos:"+elapsedTimeSinceReport.toNanos());
					if(elapsedTime.toMillis() > parameters.duration) { 
						state = LocalState.FINISH_NORMAL_RUN;
					} else if(elapsedTimeSinceReport.toNanos()/1000 > parameters.globalEpsilon) //only if duration of run is not yet done
					{
						state = LocalState.REPORT_WINDOW;
					}					 
					continue;
				case EXECUTE_LATENCY_RUN:
					 
					SimpleMessageUtilities.spinWaitMicros(parameters.timeUnitMicrosec);
					sendMessage = parameters.nextDouble() <= parameters.unicastProbability;
					if(sendMessage) 
					{
						int destination = parameters.nextDestination();
						if(destination != myIndexOfTheGroup) {
							localClock.timestampSendEvent(); 
						  	Packet packet = new Packet(MessageType.PING_LATENCY, Instant.now(), myIndexOfTheGroup);
							channel.send(SimpleMessageUtilities.getOobMessage(members.get(destination), packet));
						}
					}
			
					 elapsedTime =  Duration.between(initTime, Instant.now());
					
					 if(elapsedTime.toMillis() > parameters.duration) {  
						state = LocalState.FINISH_LATENCY_RUN;
					 }
					 
					continue;
				case FINISH_LATENCY_RUN:
					
					localStatisticsCollector.printStatistics();
					channel.send(members.get(0),new Packet(MessageType.COLLECT_LATENCY, localStatisticsCollector));
					state = LocalState.SETUP_NORMAL_RUN; 
				    	continue;
				    	
				case FINISH_NORMAL_RUN :
					localClock.timestampLocalEvent();
					localTraceCollector.pushLocalTrace(new LocalEvent(EventType.STOP,localClock, localClock.getL(),x));

					//System.out.println("Printing event at process:"+myIndexOfTheGroup);
					//localTraceCollector.printLocalTrace();
					
					/*removing hvc size computing code
					localTraceCollector.fillHvcTrace(initL, parameters.HvcCollectingPeriod,lastL);
					localTraceCollector.computeHvcSizeOverTime(parameters.duration,parameters.HvcCollectingPeriod);
					if(parameters.runQuery) {
						localTraceCollector.computeHvcSizeOverEpsilon(parameters.epsilonStart, parameters.epsilonInterval, parameters.epsilonStop);
					}
					*/
					System.out.println("Sending final set of local traces to leader.At l:"+localClock.getL().toEpochMilli()+",c:"+localClock.getC());
					Packet packet = new Packet(MessageType.CONFIG_FINISH,localTraceCollector, myIndexOfTheGroup,Instant.now(),localClock);
					Address to=members.get(0);
					channel.send(to,packet);
					
					state = LocalState.IDLE; 
					continue; 
				case REPORT_WINDOW :
					System.out.println("Reporting local traces, at l "+localClock.getL()+",c:"+localClock.getC()+", Instant"+Instant.now());
					Timestamp tempT= new Timestamp(localClock);//Variable to prevent discrepancies between window end and successive window start - due to simultaneous update of localClock
					//remembering window right-end
					localTraceCollector.setEndCausalClkInEpsilonWindow(tempT);				 
					//Packet reportPacket = new Packet(MessageType.WINDOW_REPORT,localTraceCollector, myIndexOfTheGroup,Instant.now());
					Packet reportPacket = new Packet(MessageType.WINDOW_REPORT,localTraceCollector, myIndexOfTheGroup,Instant.now(),localClock);
					Address toLeader=members.get(0);
					//sending report asynchronously
					new Thread(() -> {
						try {
							channel.send(toLeader,reportPacket);//message is not sent with out-of-band flag set so FIFO is maintained about reports from the same process
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}).start();
					//REINITIALIZE all local trace collectors
					localTraceCollector = new LocalTraceCollector(parameters.numberOfMembers, members.get(0));
					localTraceCollector.setStartCausalClkInEpsilonWindow(tempT);
					System.out.println("Window start set to L:"+localTraceCollector.getStartCausalClkInEpsilonWindow().getL()+",c:"+localTraceCollector.getStartCausalClkInEpsilonWindow().getC());
					state = LocalState.EXECUTE_NORMAL_RUN;
					//Changing the line below because we want to maintain the right end of each reporting window
					//from a process to be as close as possible to the parallel window from another process, 
					//in other words without this change if a process reports a long window then windows reported
					//by other processes may end up being far ahead of the windows following the long window
					//lastReportTime=Instant.now();
					lastReportTime=lastReportTime.plusNanos(parameters.globalEpsilon*1000);
					System.out.println("Updated lastReportTime:"+lastReportTime.toEpochMilli());
					continue;
				case STOP: 
					 
				default:
				}
				break;
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	private void localComputation() throws Exception {
		myIndexOfTheGroup = indexOfMyAddress(channel.getView().getMembers());	
		boolean thisIsTheLeader = myIndexOfTheGroup == 0;
		if(thisIsTheLeader) {
			runLeaderRoutine();
		} else {
			nonLeaderRoutine();
		}		
	}
	public String createKeyFromPktCnt(Packet receivedPacket) {
		String newKey = "<"+receivedPacket.getAllLocalEvents().getStartCausalClkInEpsilonWindow().getL();
		newKey+=","+receivedPacket.getAllLocalEvents().getStartCausalClkInEpsilonWindow().getC()+">";
		newKey+="-<"+receivedPacket.getLocalTimestamp().getL();
		newKey+=","+receivedPacket.getLocalTimestamp().getC()+">";
		return newKey;
	}
	//function does the following:
	//1.adds report to buffer, 
	//2.then tries to fill LeaderTraceCollector by processing the buffer, 
	//3.return true if buffer has a missing window
	public boolean windowToBufferToTraceCollector(Packet receivedPacket, int pktFrom) {
		//ALWAYS ADD TO BUFFER AND FILL LEADER TRACE COLLECTOR FROM THE BUFFER - 
		//to prevent using later-window leaving an earlier one in the buffer
		//Adding to Respective Process's buffer
		//create unique key
		String uniqueKey = createKeyFromPktCnt(receivedPacket);
		//updating specific process's buffer
		leaderBuffer.updateBuffer(pktFrom,uniqueKey,receivedPacket.getAllLocalEvents());		
		//Processing updated buffer -- using elements in buffer to fill in LeaderTrace						
		if (!leaderTraceCollector.hasReceivedFromAllMembers() && !leaderTraceCollector.hasReceivedFromProcess(pktFrom)) 
		{
			System.out.println("Leader trace is not filled yet.");
			//iterate through all the keys stored on our hashmap of the reporting process
			if(leaderBuffer.isBufferFilledForProcess(pktFrom)) {
				System.out.println("At P"+pktFrom+". Looping through the buffer of size "+leaderBuffer.sizeOfBufferFilledForProcess(pktFrom));
				Timestamp prevWindowRightEnd;
				//loop through buffer and use any of the elements till you find a missing window
				//previous right end L = commonStart time, and c=0 to begin with
				if(lastProcessed.get(pktFrom).getType()==TimestampType.NO_TIMESTAMP)
				{
					System.out.println("Buffer for P"+pktFrom+" is empty.Using common start value.");
					prevWindowRightEnd=new Timestamp(parameters.timestampType,this.members.size(), myIndexOfTheGroup,parameters.globalEpsilon);
					prevWindowRightEnd.setL(commonStartTime);
					prevWindowRightEnd.setC(0);
				}else {
					//get the previous window's ending timestamp stored in lastProcessed
					System.out.println("Buffer for P"+pktFrom+" is non-empty.Using previous window's rightend.");
					prevWindowRightEnd=lastProcessed.get(pktFrom);
				}																
				String remToMoveTill="";
				boolean remove= false;
				ConcurrentSkipListMap<String,LocalTraceCollector> tempBuff=leaderBuffer.getBufferofProc(pktFrom);
				for (String s :tempBuff.keySet()) 
				{//for each window-report 
					//if window start matches previous window end
					if((tempBuff.get(s).getStartCausalClkInEpsilonWindow().getL().equals(prevWindowRightEnd.getL()) && (tempBuff.get(s).getStartCausalClkInEpsilonWindow().getC()==prevWindowRightEnd.getC()))){
						//see if that can be added to the leaderTraceCollector if it belongs to matching epsilon window
						//if add was successful then remove that trace from buffer -i.e.if its in matching epsilon-window
						if(leaderTraceCollector.addTraceFrom(tempBuff.get(s),pktFrom,parameters.globalEpsilon)) {
							System.out.println("Window matched. Leader does not have a representative for process "+pktFrom+". So adding.");
							//remove it and all preceding reports from the leaderBuffer hashmap --by remembering the latest added element
							remove=true;
							remToMoveTill=s;
							break;
						} else {
							//System.out.println("Window MISMATCH for trace from "+pid);
							continue;
						}
					}else {//else exit from for loop because a report is missing
						System.out.println("Missing window.");		
						return true;
					}
				}
				//remove the elements till the marked one, remember the previous right end in lastProcessed
				if(remove) {
					lastProcessed.set(pktFrom, tempBuff.get(remToMoveTill).getEndCausalClkInEpsilonWindow());
					leaderBuffer.removeFromBufferTill(pktFrom, remToMoveTill);
				}
			}else {
				System.out.println("Unexpected behavior. Just added an element to buffer but it is empty.");
				System.exit(0);
			}
		}
		return false;
	}
	
	public static void main(String[] args) throws Exception { 
		   
		for(int i=0; i < args.length; i++) {
			System.out.println(args[i]);
			if("-bind_addr".equals(args[i])) {
	                System.setProperty("jgroups.bind_addr", args[++i]); 
	                continue;
	         }
			if("-external_addr".equals(args[i]) ){
				   System.setProperty("jgroups.external_addr", args[++i]); 
	               continue;
			}
			if("-help".equals(args[i])) {
				System.out.println("-bind_addr [addr] -external_addr [addr]");
			}
			
		} 
		
		System.setProperty("java.net.preferIPv6Addresses", "false");
		System.setProperty("java.net.preferIPv4Stack", "true");
		new SimpleMessagePassing().start();	
	}
	Timestamp localClock;
	
	JChannel channel;
	String userName=System.getProperty("user.name", "n/a");
	
	private int numWorkers;
	private Vector<Double> globalNtpOffset;
	private int myIndexOfTheGroup;
	private boolean x;
	private Instant trueUntil;
	private LocalState state; 
	private java.util.List<org.jgroups.Address> members;
	
	private LeaderTraceCollector leaderTraceCollector;
	private LeaderTraceCollector leaderTraceCollectorAhead;
	private LeaderTraceCollector leaderTraceCollectorBehind;
	private LocalTraceCollector localTraceCollector;
	
	private LocalStatisticsCollector localStatisticsCollector;
	//parameters contain global variables and local variables with parameters from the leader.
	private RunningParameters parameters; 
	private FileWriter outputLog;
	private String outputFilename;
	//private ConcurrentHashMap<String,LocalTraceCollector> leaderBuffer;
	private LeaderBuffer leaderBuffer;
	//private LeaderBuffer leaderBufferProcessed;
	private ArrayList<Timestamp> lastProcessed;
	private int ConfigFinishCounter;
	private boolean windowStart;
	private Instant lastReportTime;
	private Instant commonStartTime;
}
