import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList; 
import java.util.Collections;
import java.util.HashMap;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import java.time.Duration;
import java.time.Instant;

public class LeaderTraceCollector {
	public LeaderTraceCollector(int numProcesses, Instant initialL) {
		this.globalTrace = new ArrayList<>();
	    this.globalMsgTraces = new ArrayList<>();	    
	    this.messageSizes = new ArrayList<>();
	    this.lowerBoundsAtProcesses = new Timestamp[numProcesses];
	    this.perTraceNumRecvMessages = new int[numProcesses];
	    this.perTraceHighestCValue = new long[numProcesses];
	    this.numProcesses = numProcesses;
	    this.initialL = initialL;
		/*removing hvc size computing code
		globalHvcTrace = new ArrayList<>(); 
		globalHvcSizeOverEpsilon = new ArrayList<>();
		globalHvcSizeOverTime = new ArrayList<>();
		globalHvcSizeOverEpsilonNumEvents = new ArrayList<>();
		globalHvcSizeOverEpsilonDomain = new ArrayList<>();
		globalHvcSizeOverTimeDomain = new ArrayList<>();
		*/
		for(int i=0;i<numProcesses;i++) {
			/*removing hvc size computing code
			globalHvcTrace.add(new ArrayList<>());
			globalHvcSizeOverTime.add(new ArrayList<>());
			globalHvcSizeOverEpsilon.add(new ArrayList<>());
			globalHvcSizeOverEpsilonNumEvents.add(new ArrayList<>());
			globalHvcSizeOverEpsilonDomain.add(new ArrayList<>());
			globalHvcSizeOverTimeDomain.add(new ArrayList<>());
			*/;
			this.globalTrace.add(new ArrayList<>());
			this.globalMsgTraces.add(new ArrayList<>());
			this.lowerBoundsAtProcesses[i] = new Timestamp();
			this.perTraceNumRecvMessages[i] = 0;
			this.perTraceHighestCValue[i] = 0L;
		}
		/*removing hvc size computing code
		globalHvcSizeHistogram = new int[numProcesses];
        for(int i=0;i<this.numProcesses;i++) {
        		globalHvcSizeHistogram[i] = 0;
        }
        */
		this.globalHighestCValue = 0L;
	    this.globalTraceCounter = 0;
	    this.globalNumRecvMessages = 0;   
	}
	public void ClearLeaderTraceCollector(int numProcesses, Instant initialL)
	{
		this.globalTrace = new ArrayList<>();
		this.globalMsgTraces = new ArrayList<>();
		this.lowerBoundsAtProcesses = new Timestamp[numProcesses];
		this.messageSizes = new ArrayList<>();
		this.perTraceNumRecvMessages = new int[numProcesses];
		this.perTraceHighestCValue = new long[numProcesses];
		this.numProcesses = numProcesses;
		this.initialL = initialL;
		for (int i = 0; i < numProcesses; i++)
		{
			this.globalTrace.add(new ArrayList<>());
			this.globalMsgTraces.add(new ArrayList<>());
			this.lowerBoundsAtProcesses[i] = new Timestamp();
			this.perTraceNumRecvMessages[i] = 0;
			this.perTraceHighestCValue[i] = 0L;
		}
		this.globalHighestCValue = 0L;
		this.globalTraceCounter = 0;
		this.globalNumRecvMessages = 0;
    }
	public boolean hasReceivedFromProcess(int processId)
	{
		//received LocalTraceCollector can have 0 local events so, check if the lowerbounds are set
		if (((this.globalTrace.get(processId)).isEmpty()) && (this.lowerBoundsAtProcesses[processId].getType() == TimestampType.NO_TIMESTAMP)) {
			return false;
		}
		return true;
	}
	//Used when a new window identifier is set, to remove any representive in the LeaderTraceCollector that is far behind
	public void RemoveBehindRepresentativeTraces(LocalTraceCollector in, int from, long epsilon)
	{//remove any trace-representative in the LeaderTraceCollector that is far behind the provided LocalTraceCollector
		try
		{
			this.globalHighestCValue = 0L;//recalculate highest C value
			for (int i = 1; i < getNumberProcesses(); i++) 
			{
				if ((hasReceivedFromProcess(i)) && (i != from))//check only if the leaderTraceCollector has a representative for that process
				{
					if (in.getStartCausalClkInEpsilonWindow().getL().compareTo(getLowerBoundsAtProcesses()[i].getL()) > 0)
					{//if provided localTraceCollector is ahead
						long nanosBehind = Duration.between(getLowerBoundsAtProcesses()[i].getL(), in.getStartCausalClkInEpsilonWindow().getL()).toNanos();
						if (nanosBehind > 2L * epsilon * 1000L)//check how far behind the representative for process i is wrt the provided LocalTracecollector
						{
							this.globalTraceCounter -= 1;
							this.globalTrace.get(i).clear();
							this.globalNumRecvMessages -= getPerTraceNumRecvMessages()[i];
							this.perTraceNumRecvMessages[i] = 0;
							this.globalMsgTraces.get(i).clear();
							this.perTraceHighestCValue[i] = 0L;
							this.lowerBoundsAtProcesses[i] = new Timestamp();
						}
					}
					if (this.globalHighestCValue < this.perTraceHighestCValue[i]) {
						this.globalHighestCValue = this.perTraceHighestCValue[i];
					}
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	/*removing hvc size computing code
	public void addHvcSizeOverEpsilon(LocalTraceCollector in,int from) {
		try {  
			globalHvcSizeOverEpsilon.get(from).addAll(in.getHvcSizeOverEpsilon());
			globalHvcSizeOverEpsilonNumEvents.get(from).addAll(in.getHvcSizeOverEpsilonNumEvents());
			globalHvcSizeOverEpsilonDomain.get(from).addAll(in.getHvcSizeOverEpsilonDomain());
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	*/
	public boolean addTraceFrom(LocalTraceCollector in, int from, long epsilon) 
	{//returns true if the add was successful i.e.if provided LocalTraceCollector belongs to the epsilon window
		try
		{
			BufferedWriter writer = new BufferedWriter(new FileWriter("windowIdentifiers.txt", true));
			if (this.globalTraceCounter != 0)//if not the first trace being added to the leaderTraceCollector
			{
				writer.append("\nWindow Identifier:" + this.windowIdentifier + ", in.getStartCausalClkInEpsilonWindow().getL():" + in.getStartCausalClkInEpsilonWindow().getL() + " from " + from);
				if(this.windowIdentifier.compareTo(in.getStartCausalClkInEpsilonWindow().getL()) > 0)
				{//if window identifier is ahead
					long traceDiff = Duration.between(in.getStartCausalClkInEpsilonWindow().getL(), this.windowIdentifier).toNanos();
					System.out.println("Difference w.r.t window identifier in nanos:" + traceDiff + " for trace from " + from);
					writer.append("\n Difference w.r.t window identifier in nanos:" + traceDiff + " for trace from " + from);
					if (traceDiff > 2L * epsilon * 1000L)//if received window is far behind
					{
						writer.close();
						return false;
					}
				}
				else
				{//if window identifier is behind
					long traceDiff = Duration.between(this.windowIdentifier, in.getStartCausalClkInEpsilonWindow().getL()).toNanos();
					System.out.println("Window Identifier is behind.Diff w.r.t window identifier in nanos:" + traceDiff + " for trace from " + from);
					writer.append("\nWindow Identifier is behind.Diff w.r.t window identifier in nanos:" + traceDiff + " for trace from " + from);
					if (traceDiff > 2L * epsilon * 1000L)//epsilon is in microseconds
					{//if window identifier is far behind
						this.windowIdentifier = in.getStartCausalClkInEpsilonWindow().getL();
						System.out.println("Changed Window Identifier as " + this.windowIdentifier + " from " + from);
						writer.append("\nChanged Window Identifier as " + this.windowIdentifier + " from " + from);
						if (this.globalTraceCounter == 1)
						{
							ClearLeaderTraceCollector(getNumberProcesses(), getInitialL());
							writer.append("\nClearing LeaderTrace");
						}
						else
						{
							RemoveBehindRepresentativeTraces(in, from, epsilon);
							writer.append("\nRemoving behind reps in LeaderTrace");
						}
					}
				}
			}
			else //first trace-representative being added to the LeaderTraceCollector
			{
				this.windowIdentifier = in.getStartCausalClkInEpsilonWindow().getL();
				System.out.println("Setting Window Identifier as " + this.windowIdentifier + " from " + from);
				writer.append("\nglobalTraceCounter=0,Setting Window Identifier as " + this.windowIdentifier + " from " + from);
			}
			this.globalTraceCounter += 1;
			this.globalTrace.get(from).addAll(in.getLocalTrace());
			this.messageSizes.addAll(in.getMessageSizes());
			this.globalNumRecvMessages += in.getNumRecvMessages();
			this.lowerBoundsAtProcesses[from] = new Timestamp(in.getStartCausalClkInEpsilonWindow());
			System.out.println("Set lower bound for " + from);
			this.lowerBoundsAtProcesses[from].print();
			System.out.println("in epochmilli:" + this.lowerBoundsAtProcesses[from].getL().toEpochMilli());
		    this.globalMsgTraces.get(from).addAll(in.getLocalMsgTraces());
			//globalHvcTrace.get(from).addAll(in.getHvcTrace());
			/*removing hvc size computing code
			globalHvcSizeOverTime.get(from).addAll(in.getHvcSizeOverTime());
			globalHvcSizeOverTimeDomain.get(from).addAll(in.getHvcSizeOverTimeDomain());
			int localHvcSizeHistogram [] = in.getHvcSizeHistogram();
			for(int i=0;i<this.numProcesses;i++) {
			globalHvcSizeHistogram[i] += localHvcSizeHistogram[i];
			}
			*/
		    long maxCValue = in.getLocalHighestCValue();
		    if (this.globalHighestCValue < maxCValue) {
		    	this.globalHighestCValue = maxCValue;
		    }
		    writer.close();
		}
	    catch (IOException e1)
	    {
	      e1.printStackTrace();
	    }
	    return true;
	} 
	public boolean hasReceivedFromAllMembers() { 
		return globalTraceCounter==numProcesses-1;		
	}
	public void printGlobalTrace() {
		int i=0;
		for(ArrayList<LocalEvent> ve : globalTrace) {
			System.out.println("Printing event from process : " + Integer.toString(i++));
			for(LocalEvent e : ve) {
				e.localCausalityClock.print();
				System.out.println(e.eventType);
				if(e.x) {/****content to be removed*/
					//long epochequiva=e.localCausalityClock.getL().toEpochMilli();
					//long nano=e.localCausalityClock.getL().getNano();// returns 9 digits
					//long lInNano=((epochequiva*1000000)+(nano%1000000));//use only last 6 digits since first 3 are in epochMilli
					//Instant instantEquiva = Instant.ofEpochMilli((lInNano-lInNano%1000000)/1000000);
					//System.out.println("Instant value of l from nanoseconds:"+instantEquiva);	
					System.out.println("x:"+e.x);
				}
			}
			System.out.println("---------");
		}
		for(ArrayList<MsgTrace> mts : globalMsgTraces) {
			System.out.println("Printing msg trace from process : " + Integer.toString(i++));
			for(MsgTrace mt : mts) {
				System.out.println("Message from:"+mt.senderIndex+" sender timestamp:"+mt.senderCausalityClock.getL()+" Sender Wall Clock time:"+mt.senderWallClock);
				System.out.println("To:"+mt.receiverIndex+" receiver timestamp:"+mt.receiverCausalityClock.getL()+" Receiver Wall Clock time:"+mt.receiverWallClock);
			}
			System.out.println("---------");
		}
	}
	public void solveConstraints(long epsilon, int numberOfProcesses, FileWriter outputLog)
	{	
		try {
			FileWriter constraintLog = new FileWriter("./" + Instant.now().toEpochMilli() + ".txt", false);
			HashMap<String, String> options = new HashMap<>();
			options.put("proof", "true");
			options.put("model", "true");
			options.put("unsat_core", "true");
			final Context context1 = new Context(options);
			final Solver solver1 = context1.mkSimpleSolver();
			//add epsilon constraints
			for (int i=1; i<numberOfProcesses; i++) {
				for (int j=1; j<numberOfProcesses; j++) {
					if(i!=j)
					{
						IntExpr li = context1.mkIntConst("l"+i);
						IntExpr lj = context1.mkIntConst("l"+j);
						//Format: "(assert (or (and (< (- l"<<pr<<" l"<<pr1<<") "<<4*epsilon<<") (>= (- l"<<pr<<" l"<<pr1<<") 0)) (and (< (- l"<<pr1<<" l"<<pr<<") "<<4*epsilon<<") (> (- l"<<pr1<<" l"<<pr<<") 0))))\n";
						ArithExpr lDiff1 = context1.mkSub(li,lj);
						double epsMultiHighesC=globalHighestCValue*(epsilon/1000.0); //input epsilon is in microseconds and l value granularity is milliseconds
						BoolExpr lDiff1UpperBound = context1.mkLt(lDiff1, context1.mkReal(String.valueOf(epsMultiHighesC)));
						BoolExpr lDiff1LowerBound = context1.mkGe(lDiff1, context1.mkInt(0));
						BoolExpr arg0 = context1.mkAnd(lDiff1UpperBound,lDiff1LowerBound);
						ArithExpr lDiff2 = context1.mkSub(lj,li);
						BoolExpr lDiff2UpperBound = context1.mkLt(lDiff2, context1.mkReal(String.valueOf(epsMultiHighesC)));
						BoolExpr lDiff2LowerBound = context1.mkGe(lDiff2, context1.mkInt(0));
						BoolExpr arg1 = context1.mkAnd(lDiff2UpperBound,lDiff2LowerBound);
						BoolExpr epsConstraint = context1.mkOr(arg0,arg1);
				        //System.out.println("Adding "+epsConstraint);
				        constraintLog.write(epsConstraint.toString() + "\n");
						solver1.add(epsConstraint);
					}
				}
			}		
			//parse local event traces in the global trace-- to create interval constraints
			int processId = 0;
			long rightEndValue = 0L;long prevRightEnd = 0L;
			BoolExpr xvalue;
			BoolExpr upperBound;
			for (ArrayList<LocalEvent> ve : this.globalTrace)
			{
				//local event trace of each process
				IntExpr l = context1.mkIntConst("l" + processId);
				BoolExpr x = context1.mkBoolConst("x" + processId);
				for (LocalEvent e : ve)
				{//for each local event
					xvalue = context1.mkBool(e.x);
					if (prevRightEnd == 0L)
					{
						Timestamp lowerEnd = this.lowerBoundsAtProcesses[processId];
						if (this.globalHighestCValue != 0L) {
							prevRightEnd = lowerEnd.getL().toEpochMilli() * this.globalHighestCValue + lowerEnd.getC();
						} else {
							prevRightEnd = lowerEnd.getL().toEpochMilli();
						}
						BoolExpr lowerBound = context1.mkGe(l, context1.mkInt(prevRightEnd));
						constraintLog.write(lowerBound.toString() + "\n");
						solver1.add(lowerBound);
					}
					IntExpr leftEnd = context1.mkInt(prevRightEnd);
					long epochequiva = e.localCausalityClock.getL().toEpochMilli();
					if (this.globalHighestCValue != 0L)
					{
						rightEndValue = epochequiva * this.globalHighestCValue + e.localCausalityClock.getC();
					}
					else
					{
						if (e.localCausalityClock.getC() != 0L) {
						System.out.println("Warning: Incorrect computation of highest c seen so far as 0.c:" + e.localCausalityClock.getC());
						}
						rightEndValue = epochequiva;
					}
					IntExpr rightEnd = context1.mkInt(rightEndValue);
					prevRightEnd = rightEndValue;		  
					BoolExpr impLeft = context1.mkAnd(new BoolExpr[] { context1.mkLe(leftEnd, l), context1.mkLt(l, rightEnd) });
					BoolExpr impRight = context1.mkEq(x, xvalue);
					BoolExpr intervalConstraint = context1.mkImplies(impLeft, impRight);
					  
					solver1.add(intervalConstraint);
					constraintLog.write(intervalConstraint.toString() + "\n");
				}
				if (ve.size() != 0)//if no local events were encountered then no lowerbound/upperbound gets set --UPDATE???
				{
					upperBound = context1.mkLt(l, context1.mkInt(rightEndValue));		  
					constraintLog.write(upperBound.toString() + "\n");
					solver1.add(upperBound);
				}
				processId++;
				rightEndValue = 0L;
				prevRightEnd = 0L;
			}
			//parse message traces and generate message constraints
			processId = 0;
			long epochequiva;
			for (ArrayList<MsgTrace> msgTr : this.globalMsgTraces)
			{//generate message constraints
				IntExpr l = context1.mkIntConst("l" + processId);
				for (MsgTrace mt : msgTr)
				{
					epochequiva = mt.receiverCausalityClock.getL().toEpochMilli();
					long receiverValue;
					if (this.globalHighestCValue != 0L)
					{
						receiverValue = epochequiva * this.globalHighestCValue + mt.receiverCausalityClock.getC();
					}
					else
					{
						if (mt.receiverCausalityClock.getC() != 0L) 
						{
							System.out.println("Warning: Incorrect comoutation of highest c seen so far as 0.c:" + mt.receiverCausalityClock.getC());
							System.exit(0);
						}
						receiverValue = epochequiva;
					}
					IntExpr receiverValueExpr = context1.mkInt(receiverValue);
					BoolExpr impLeft = context1.mkGe(l, receiverValueExpr);
					IntExpr lOtherProcess = context1.mkIntConst("l" + mt.senderIndex);
					long senderValue;
					if (this.globalHighestCValue != 0L)
					{
						senderValue = mt.senderCausalityClock.getL().toEpochMilli() * this.globalHighestCValue + mt.senderCausalityClock.getC();
					}
					else
					{
						if (mt.senderCausalityClock.getC() != 0L) 
						{
							System.out.println("Warning: Incorrect computation of highest c seen so far as 0.c:" + mt.senderCausalityClock.getC());
							System.exit(0);
						}
						senderValue = mt.senderCausalityClock.getL().toEpochMilli();
					}
					IntExpr lOtherProcessValueExpr = context1.mkInt(senderValue);
					BoolExpr impRight = context1.mkGt(lOtherProcess, lOtherProcessValueExpr);
					BoolExpr msgConstraint = context1.mkImplies(impLeft, impRight);
					//add to solver
					solver1.add(new BoolExpr[] { msgConstraint });
					constraintLog.write(msgConstraint.toString() + "\n");
					}
				processId++;
			}
			//add predicate to be detected
			for (int j=1; j<numberOfProcesses; j++) {
					BoolExpr xj = context1.mkBoolConst("x"+j);
					BoolExpr predicateConstraint = context1.mkEq(xj, context1.mkTrue());
					//System.out.println(predicateConstraint);
					constraintLog.write(predicateConstraint.toString() + "\n");
					solver1.add(predicateConstraint);
			}
			//check sat/unsat
			solver1.check();
			Status result1 = solver1.check();
			if(result1.equals(Status.SATISFIABLE)){
				final Model model = solver1.getModel();
				outputLog.write("\nModel check result  " + result1);
				System.out.println("Model check result  " + result1);
				//System.out.println(model);
				Instant maxL = Instant.MIN;
				for (FuncDecl constExpr : model.getConstDecls()) {//get interpretation of all consts in the model
					if (constExpr.getName().toString().contains("l")) {
						Instant instantEquiva;
						if (this.globalHighestCValue != 0L) {
							instantEquiva = Instant.ofEpochMilli(Long.parseLong(model.getConstInterp(constExpr).toString()) / this.globalHighestCValue);
						} else {
							instantEquiva = Instant.ofEpochMilli(Long.parseLong(model.getConstInterp(constExpr).toString()));
						}
						if (instantEquiva.isAfter(maxL)) {
							maxL = instantEquiva;
						}
						System.out.println(constExpr.getName()+":"+instantEquiva);
					} else {
						System.out.println(constExpr.getName()+":"+model.getConstInterp(constExpr));
					}
				}
				Instant instNow = Instant.now();
				System.out.println("\nVerified at " + instNow);	        
				System.out.println("Duration between maxL and now in mu-s: " + Duration.between(maxL, instNow).toNanos() / 1000L);
			} 
			else if (result1.equals(Status.UNSATISFIABLE))
			{
				System.out.println("Model check result  " + result1);
				outputLog.write("\nModel check result  " + result1);
				//System.out.println("Proof:"+solver1.getProof());
				if (solver1.getUnsatCore().length!=0) {
					//System.out.println(solver1.getUnsatCore().toString());
				}		
				Instant instNow = Instant.now();
				System.out.println("\nVerified at " + instNow);
			}
			else
			{
				System.out.println("Warning: Solution " + result1);
				Instant instNow = Instant.now();
				System.out.println("\nVerified at " + instNow);
			}
			context1.close();
			constraintLog.close();
		}
		catch (IOException e1)
	    {
			e1.printStackTrace();
		}
	}
	
	/*
	public void writeHvcSizeOverTimeAvgToFile(String name, FileWriter outputLog, String outputFilename)  {

		try {
			
			outputLog.write(name);	
			outputLog.write(System.getProperty( "line.separator" ));
			
			FileWriter file = new FileWriter("./hvcSizeOverTimeAvg" + outputFilename+ name,false);
			int traceLength = globalHvcSizeOverTime.get(1).size();
			
			for(int i=0;i<traceLength;i++) {
				double sum = 0;
			 
				for(int j=1;j<numProcesses;j++) {
					sum += globalHvcSizeOverTime.get(j).get(i);
			
				}
				sum = sum/(numProcesses-1);
				
				outputLog.write((Duration.between(initialL, globalHvcSizeOverTimeDomain.get(1).get(i))).toMillis()+ " "+ Double.toString(sum));
				outputLog.write(System.getProperty( "line.separator" ));
			
				file.write((Duration.between(initialL, globalHvcSizeOverTimeDomain.get(1).get(i))).toMillis()+ " "+ Double.toString(sum));
				file.write(System.getProperty( "line.separator" ));
			}
			
			file.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void writeHvcSizeOverTimeRawToFile(String name, FileWriter outputLog,String outputFilename)  {

		try {
			
			outputLog.write(name);	
			outputLog.write(System.getProperty( "line.separator" ));
		
		
			FileWriter file = new FileWriter("./hvcSizeOverTimeRaw" + outputFilename + name,false);
			int traceLength = globalHvcSizeOverTime.get(1).size();
			
			for(int i=0;i<traceLength;i++) { 
				file.write((Duration.between(initialL, globalHvcSizeOverTimeDomain.get(1).get(i))).toMillis()+ " ");
				outputLog.write((Duration.between(initialL, globalHvcSizeOverTimeDomain.get(1).get(i))).toMillis()+ " ");
				for(int j=1;j<numProcesses;j++) {
					file.write(globalHvcSizeOverTime.get(j).get(i)+" ");
					outputLog.write(globalHvcSizeOverTime.get(j).get(i)+" ");
				}
				file.write(System.getProperty( "line.separator" ));
				outputLog.write(System.getProperty( "line.separator" ));
			}
			
			file.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	
	public void writeHvcSizeHistogramSnapsnotToFile(String name, FileWriter outputLog, String outputFilename) throws IOException {
		 
		int [] frequency = new int[numProcesses];
		int traceLength = globalHvcSizeOverTime.get(1).size();	
		int totalFrequency =0;
		for(int i=0;i<traceLength;i++) {
			for(int j=1;j<numProcesses;j++) {
				frequency[globalHvcSizeOverTime.get(j).get(i)]++;
				totalFrequency++;
			}
		}
		outputLog.write(name);	
		outputLog.write(System.getProperty( "line.separator" ));
		
		SimpleMessageUtilities.writeHistogramToFile("hvcSizeHistogramSnapsnot"+outputFilename+name, frequency, totalFrequency, outputLog);
		
	}
 	
	public void writeHvcSizeHistogramToFile(String name, FileWriter outputLog, String outputFilename) throws IOException {
		
		int totalNumEvents = 0;
		for(int i=1;i<this.numProcesses;i++) {
			totalNumEvents += globalHvcSizeHistogram[i]; 
		}
		
		outputLog.write(name);	
		outputLog.write(System.getProperty( "line.separator" ));
		
				
		SimpleMessageUtilities.writeHistogramToFile("hvcSizeHistogram"+outputFilename+name, globalHvcSizeHistogram, totalNumEvents,outputLog);
	}
 	
	public void writeHvcSizeOverEpsilonToFile(String name, FileWriter outputLog, String outputFilename) {
		try {
			
			outputLog.write(name);	
			outputLog.write(System.getProperty( "line.separator" ));
		
			FileWriter file = new FileWriter("./hvcSizeOverEpsilon" + outputFilename+name,false);
			int traceLength = globalHvcSizeOverEpsilon.get(1).size();
			
			for(int i=0;i<traceLength;i++) {
				double sum = 0;
				int num_events = 0;
			
				for(int j=1;j<numProcesses;j++) {
					sum += globalHvcSizeOverEpsilon.get(j).get(i);
					num_events += globalHvcSizeOverEpsilonNumEvents.get(j).get(i);
				}
				sum = sum/num_events;
				//sum = sum/(numProcesses-1); 
				outputLog.write(globalHvcSizeOverEpsilonDomain.get(1).get(i)+" "+Double.toString(sum));
				outputLog.write(System.getProperty( "line.separator" ));
			
				file.write(globalHvcSizeOverEpsilonDomain.get(1).get(i)+" "+Double.toString(sum));
				file.write(System.getProperty( "line.separator" ));
			}
			file.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
	
		}
	}
	*/
	public void printStatistics(RunningParameters parameters) {
		System.out.println("Number of messages = " + globalNumRecvMessages);
		//System.out.println("Theoretical number of messages = " + );
		double theoreticalThroughput= 1000000.0*parameters.unicastProbability/parameters.timeUnitMicrosec;
		double observedThroughput = 1000*globalNumRecvMessages/((double)parameters.duration*(numProcesses-1.0));
		System.out.println("Observed Throughput = " +  observedThroughput + " msgs/sec/node");
		System.out.println("Theoretical throughput = "+ theoreticalThroughput + " msgs/sec/node");
		
		System.out.println("Performance ratio = " +  observedThroughput/theoreticalThroughput);
		
	}

	public void logStatistics(FileWriter outputLog,RunningParameters parameters) throws IOException {

	
		outputLog.write("---------------- ");
		outputLog.write(System.getProperty( "line.separator" ));
		double theoreticalThroughput= 1000000.0*parameters.unicastProbability/parameters.timeUnitMicrosec;
		double observedThroughput = 1000*globalNumRecvMessages/((double)parameters.duration*(numProcesses-1.0));
		outputLog.write("| Observed Throughput = " +  observedThroughput + " msgs/sec/node");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("| Theoretical throughput = "+ theoreticalThroughput + " msgs/sec/node");
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("| Performance ratio = " +  observedThroughput/theoreticalThroughput);
		outputLog.write(System.getProperty( "line.separator" ));
		outputLog.write("---------------- ");
		outputLog.write(System.getProperty( "line.separator" ));
		
		if(!messageSizes.isEmpty()) {
			Collections.sort(messageSizes); 
			outputLog.write("Number of messages = " + globalNumRecvMessages);
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("---------------- ");
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("| Average message size: " + SimpleMessageUtilities.average(messageSizes) + " B");
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("| Minimum message size: " + messageSizes.get(0)  + " B"); 
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("| Median message size: " + (messageSizes.get(messageSizes.size()/2)+messageSizes.get(messageSizes.size()/2-1))/2.0  + " B"); 
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("| Maximum message size: " + messageSizes.get(messageSizes.size()-1) + " B");
			outputLog.write(System.getProperty( "line.separator" ));
			outputLog.write("-----------------");
			outputLog.write(System.getProperty( "line.separator" ));
		}
	}
	public void appendLeaderTrace(LeaderTraceCollector otherTrace)
	{
		try
		{
			if (otherTrace.isEmpty()) {
				return;
			}
			if (this.globalTraceCounter == 0) {
				setLowerBoundsAtProcesses(otherTrace.getLowerBoundsAtProcesses());
			}
			//the otherTrace being appended is always completely filled- has representatives from all processes
			this.globalTraceCounter = otherTrace.getGlobalTraceCounter();
			for (int i = 1; i < getNumberProcesses(); i++){
				this.globalTrace.get(i).addAll(otherTrace.getGlobalTrace().get(i));
				this.messageSizes.addAll(otherTrace.getMessageSizes());
				this.globalNumRecvMessages += otherTrace.getGlobalNumRecvMessages();
				this.globalMsgTraces.get(i).addAll(otherTrace.getGlobalMsgTrace().get(i));
				long maxCValue = otherTrace.getGlobalHighestCValue();
				if (this.globalHighestCValue < maxCValue) {
					this.globalHighestCValue = maxCValue;
				}
				this.perTraceNumRecvMessages[i] += otherTrace.getPerTraceNumRecvMessages()[i];
				if (this.perTraceHighestCValue[i] < otherTrace.getPerTraceHighestCValue()[i]) {
					this.perTraceHighestCValue[i] = otherTrace.getPerTraceHighestCValue()[i];
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	public void printLowerBounds()
	{
		System.out.println("current lower bounds:");
		for (int i = 0; i < this.lowerBoundsAtProcesses.length; i++)
		{
			System.out.println(" at <" + i + ">");
			this.lowerBoundsAtProcesses[i].print();
		}
	}
	public void setLowerBoundsAtProcesses(Timestamp[] bounds)
	{
		for (int i = 0; i < this.lowerBoundsAtProcesses.length; i++) {
			//setLowerBoundsAtProcesses is called only in appendTrace where 
			//the trace being appended has its own lower bounds that are never 
			//updated after being set, so there is no need a new Timestamp instance
			this.lowerBoundsAtProcesses[i] = bounds[i];
		}
	}
	public boolean isEmpty()
	{
		if (this.globalTraceCounter == 0) {
			return true;
		}
		return false;
	}
	public int getNumberProcesses()
	{
		return this.numProcesses;
	}
	public int getGlobalNumRecvMessages()
	{
		return this.globalNumRecvMessages;
	}
	public int[] getPerTraceNumRecvMessages()
	{
		return this.perTraceNumRecvMessages;
	}
	public int getGlobalTraceCounter()
	{
		return this.globalTraceCounter;
	}
	public Instant getInitialL()
	{
		return this.initialL;
	}
	public ArrayList<ArrayList<LocalEvent>> getGlobalTrace()
	{
		return this.globalTrace;
	}
	public ArrayList<ArrayList<MsgTrace>> getGlobalMsgTrace()
	{
		return this.globalMsgTraces;
	}
	public Timestamp[] getLowerBoundsAtProcesses()
	{
		return this.lowerBoundsAtProcesses;
	}
	public long getGlobalHighestCValue()
	{
		return this.globalHighestCValue;
	}
	public long[] getPerTraceHighestCValue()
	{
		return this.perTraceHighestCValue;
	}
	public ArrayList<Long> getMessageSizes()
	{
		return this.messageSizes;
	}
	private Instant initialL;
	private int numProcesses;
	private int globalTraceCounter;
	private ArrayList<Long> messageSizes;
	private ArrayList<ArrayList<LocalEvent>> globalTrace;
	private int globalNumRecvMessages;
	private ArrayList<ArrayList<MsgTrace>> globalMsgTraces;
	private long globalHighestCValue;
	private Timestamp[] lowerBoundsAtProcesses;
	private Instant windowIdentifier;
	private long[] perTraceHighestCValue;
	private int[] perTraceNumRecvMessages;
	/*removing hvc size computing code
	private ArrayList<ArrayList<LocalEvent>> globalHvcTrace;
	private ArrayList<ArrayList<Integer>> globalHvcSizeOverTime;
	private ArrayList<ArrayList<Integer>> globalHvcSizeOverEpsilon;
	private ArrayList<ArrayList<Long>> globalHvcSizeOverEpsilonDomain;
	private ArrayList<ArrayList<Instant>> globalHvcSizeOverTimeDomain;
	private ArrayList<ArrayList<Integer>> globalHvcSizeOverEpsilonNumEvents;
	private int [] globalHvcSizeHistogram;
	*/
}
