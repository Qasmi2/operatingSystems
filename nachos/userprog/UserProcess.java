package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.*;
/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	protected PageManager pageManager;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';

	// Other data that a process should keep track of:
	/** This process' assigned process ID, to be used mainly as an identifier for this process. **/
	private int processId;

	/** The process' status, to be used in exit and join syscalls. **/
	private int status;

	/** Stores a reference to the thread associated with this process.  According to @295 on Piazza, 
	 * we assume that each UserProcess can only have one associated <tt>UThread</tt>.
	 */
	private UThread thread;

	/** This map links a processId with the UserProcess representing the child process. **/
	private Map<Integer, UserProcess> childProcesses;

	// Parent.
	private int parentProcessId;
	private UserProcess parentProcess;

	/** Reference to this process' associated <tt>FileDescriptorManager</tt>.  Keeps track of all 
	 * of the individual file descriptors associated with the process.
	 */
	private FileDescriptorManager fileDescriptors;

	/** Static counter which keeps track of the next unassigned processId which we can assign. **/
	private static int nextProcessId = 0;

	public class PageManager {
		public static final int INVALID_PAGE = -1;
		public LinkedList<UserKernel.Page> freePages = null;
		public LinkedList<UserKernel.Page> usedPages = null;
		public UserProcess parentProcess = null;

		public PageManager(LinkedList<UserKernel.Page> globalFreePages, UserProcess uProcess) {
			Lib.assertTrue(globalFreePages!=null);
			Lib.assertTrue(uProcess!=null);
			this.freePages = globalFreePages;
			this.parentProcess = uProcess;
			usedPages = new LinkedList<UserKernel.Page>();
		}

		public UserKernel.Page getFreePage() {
			Lib.assertTrue(freePages!=null);
			boolean intStatus = Machine.interrupt().disable();
			if (!freePages.isEmpty()) {
				UserKernel.Page page = freePages.poll();
				Machine.interrupt().restore(intStatus);
				return page;
			} else {
				Machine.interrupt().restore(intStatus);
				return null;
			}
		}

		public void addFreePage(UserKernel.Page p) {
			Lib.assertTrue(freePages!=null);
			Lib.assertTrue(p!=null);
			boolean intStatus = Machine.interrupt().disable();
			freePages.push(p);
			Machine.interrupt().restore(intStatus);
		}

		public void freePage(UserKernel.Page p) {
			Lib.assertTrue(usedPages!=null);
			Lib.assertTrue(p!=null);
			boolean intStatus = Machine.interrupt().disable();
			p.free();
			freePages.push(p);
			Machine.interrupt().restore(intStatus);
		}

		public TranslationEntry getEntry(int vpn) {
			for(TranslationEntry te: parentProcess.pageTable) {
				if (vpn == te.vpn) {
					return te;
				}
			}
			Lib.assertTrue(false); //should never ask to get an Entry in Page Table that isn't there
			return null;
		}

		public void exit() {
			Lib.assertTrue(usedPages!=null);
			boolean intStatus = Machine.interrupt().disable();
			for(TranslationEntry te: parentProcess.pageTable) {
				for(int i=0; i<usedPages.size(); i++) {
					UserKernel.Page page = usedPages.get(i);
					if (te.ppn == page.ppn) {
						freePage(page);
					}
				}
			}
			Machine.interrupt().restore(intStatus);
		}
	}

	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		pageManager = new PageManager(UserKernel.freePages, this);
		for (int i=0; i<numPhysPages; i++)
			pageTable[i] = new TranslationEntry(i,i, true,false,false,false);
		
		// Added code here.
		processId = nextProcessId++;
		status = Constants.STATUS_READY;
		fileDescriptors = new FileDescriptorManager();
		
		childProcesses = new HashMap<Integer, UserProcess>();
		parentProcessId = Constants.NO_PROCESS_ID;
	}

	/**
	 * Allocate and return a new process of the correct class. The class name
	 * is specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return	a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
	}
	
	/**
	 * <p>Allocate and return a new process of the correct class.  The class name 
	 * is specified by the <tt>nachos.conf</tt> key <tt>Kernel.processClassName</tt>. 
	 * Atomically updates parent-child relationship between processes.
	 * @param parentId is the parent's processID
	 * @param parentProcess is the associated parent process
	 * @return a new process of the correct class, with parent and child data appropriately 
	 * populated.
	 */
	public static UserProcess newUserProcess(int parentId, UserProcess parentProcess) {
		boolean interruptStatus = Machine.interrupt().disable();
		UserProcess newProcess = newUserProcess();
		newProcess.parentProcessId = parentId;	// Set parent.
		newProcess.parentProcess = parentProcess;
		newProcess.parentProcess.childProcesses.put(newProcess.processId, newProcess);	// Update parent's children.
		Machine.interrupt().restore(interruptStatus);
		return newProcess;
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param	name	the name of the file containing the executable.
	 * @param	args	the arguments to pass to the executable.
	 * @return	<tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read
	 * at most <tt>maxLength + 1</tt> bytes from the specified address, search
	 * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param	vaddr	the starting virtual address of the null-terminated
	 *			string.
	 * @param	maxLength	the maximum number of characters in the string,
	 *				not including the null terminator.
	 * @return	the string read, or <tt>null</tt> if no null terminator was
	 *		found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength+1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length=0; length<bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param	vaddr	the first byte of virtual memory to read.
	 * @param	data	the array where the data will be stored.
	 * @return	the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no
	 * data could be copied).
	 *
	 * @param	vaddr	the first byte of virtual memory to read.
	 * @param	data	the array where the data will be stored.
	 * @param	offset	the first byte to write in the array.
	 * @param	length	the number of bytes to transfer from virtual memory to
	 *			the array.
	 * @return	the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset,
			int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length) {
			return 0;
		}
		int bytesRead = 0;
		int dataIndex = offset - 1;
		for (int i = 0; i < length; i++) {
			TranslationEntry entry  = pageManager.getEntry(vaddr);
			if (entry == null) { //Nothing more to read
				return bytesRead;
			}
			Lib.assertTrue(!entry.readOnly);
			data[dataIndex] = memory[entry.ppn + (vaddr - entry.vpn)];
			dataIndex++;
			bytesRead++;
		}
		return 0;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory.
	 * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param	vaddr	the first byte of virtual memory to write.
	 * @param	data	the array containing the data to transfer.
	 * @return	the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no
	 * data could be copied).
	 *
	 * @param	vaddr	the first byte of virtual memory to write.
	 * @param	data	the array containing the data to transfer.
	 * @param	offset	the first byte to transfer from the array.
	 * @param	length	the number of bytes to transfer from the array to
	 *			virtual memory.
	 * @return	the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset,
			int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;
		int bytesWritten = 0;
		int dataIndex = offset - 1;
		for (int i = 0; i < length; i++) {
			TranslationEntry entry  = pageManager.getEntry(vaddr);
			if (entry == null) { //Nothing more to write to in physical memory 
				return bytesWritten;
			}
			Lib.assertTrue(!entry.readOnly);
			memory[entry.ppn + (vaddr - entry.vpn)] = data[dataIndex];
			dataIndex++;
			bytesWritten++;
		}
		return 0;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param	name	the name of the file containing the executable.
	 * @param	args	the arguments to pass to the executable.
	 * @return	<tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i=0; i<args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();	

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages*pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages-1)*pageSize;
		int stringOffset = entryOffset + args.length*4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i=0; i<argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
					argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be
	 * run (this is the last step in process initialization that can fail).
	 *
	 * @return	<tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// load sections
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i=0; i<section.getLength(); i++) {
				int vpn = section.getFirstVPN()+i;
				TranslationEntry tEntry = pageManager.getEntry(vpn);
				// for now, just assume virtual addresses=physical addresses
				section.loadPage(i, tEntry.ppn);
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
	}    

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of
	 * the stack, set the A0 and A1 registers to argc and argv, respectively,
	 * and initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i=0; i<processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call. 
	 */
	private int handleHalt() {

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}
	
	// Part 3 System Calls
	/**
	 * handleExit() is the handler for an exit syscall.  It is responsible for closing any 
	 * associated open files, updating the child's parent pointers, freeing up any held 
	 * resources, and terminating the associated thread.
	 * @param status is the status to be returned to the parent process.
	 */
	private void handleExit(int status) {
		boolean interruptStatus = Machine.interrupt().disable();
		
		// Close all files in file descriptors.
		fileDescriptors.exit();	
		
		// Clear all parent data for this process' children.
		int numChildren = childProcesses.size();
		UserProcess[] childProcessesList = childProcesses.keySet().toArray(new UserProcess[numChildren]);
		for (UserProcess child : childProcessesList) {
			child.resetParent();
		}
		
		// Free up resources for this process and set appropriate statuses.
		unloadSections();	
		this.status = Constants.STATUS_EXITED;
		parentProcess.status = status;	// TODO Is this what they mean by returning the status to the parent?
		
		Machine.interrupt().restore(interruptStatus);
		
		// Finish or terminate the thread.
		if (processId == 0) {	// TODO Is this a valid assumption, that process 0 is always last to go?
			Kernel.kernel.terminate();
		} else {
			KThread.finish();
		}
	}
	
	/**
	 * handleJoin() is the handler for the join syscall.  After performing validation, it executes 
	 * the joining and disowns the child to ensure that it is joined only once.
	 * @param processId is the process ID for the child to be joined with.
	 * @param statusAddr is the virtual address we are to write the child's exit status to.
	 * @return an exit code indicating success or failure
	 */
	private int handleJoin(int processId, int statusAddr) {
		UserProcess child = childProcesses.get(processId);
		if (child == null) {
			return Constants.NO_PROCESS_ID;
		}
		
		boolean interruptStatus = Machine.interrupt().enabled();
		try {
			child.thread.join();	// Return immediately if child process is done, otherwise wait.
			
			interruptStatus = Machine.interrupt().disable();	// TODO Would a lock be better instead?
			int childExitStatus = child.status;
			child.resetParent();
			childProcesses.remove(processId);
			
			ByteBuffer b = ByteBuffer.allocate(4);
			b.putInt(childExitStatus);
			int bytesTransferred = writeVirtualMemory(statusAddr, b.array());
			if (bytesTransferred != 4) {
				Machine.interrupt().restore(interruptStatus);
				return Constants.JOIN_ERROR_CODE;
			} else {
				Machine.interrupt().restore(interruptStatus);
				return Constants.JOIN_SUCCESS_CODE;
			}
			
		} catch (Exception e) {
			Machine.interrupt().restore(interruptStatus);
			return Constants.JOIN_ERROR_CODE;
		}
	}
	
	/**
	 * handleExec() is the handler method which processes the exec syscall.  It is responsible for 
	 * validating arguments provided and executing the stored program in a child process.
	 * @param fileAddr is the pointer to the file name in virtual memory
	 * @param argc is the number of arguments to pass into the child process
	 * @param argvAddr is an array of pointers to each argument
	 * @return processId of the child process, or an error code if unsuccessful
	 */
	private int handleExec(int fileAddr, int argc, int[] argvAddr) {
		// Retrieve and validate file name.
		String filename = readVirtualMemoryString(fileAddr, Constants.MAX_ARG_LENGTH);
		if (!filename.endsWith(".coff")) {
			return Constants.EXEC_ERROR_CODE;
		}
		
		// Retrieve and validate arguments.
		if (argc < 0 || argc != argvAddr.length) {
			return Constants.EXEC_ERROR_CODE;
		}
		String[] argValues = new String[argc];
		for (int i = 0; i < argc; i++) {
			argValues[i] = readVirtualMemoryString(argvAddr[i], Constants.MAX_ARG_LENGTH);
		}
		
		// Create new child process, then execute.
		UserProcess childProcess = newUserProcess(processId, this);
		boolean execIsSuccessful = childProcess.execute(filename, argValues);
		if (execIsSuccessful) {
			return childProcess.processId;
		} else {
			return Constants.EXEC_ERROR_CODE;
		}
	}
	
	/**
	 * resetParent() is a helper function which serves to reset the parent data.
	 */
	private void resetParent() {
		parentProcessId = Constants.NO_PROCESS_ID;
		parentProcess = null;
	}


	private static final int
	syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreate = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * <table>
	 * <tr><td>syscall#</td><td>syscall prototype</td></tr>
	 * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
	 * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
	 * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td></tr>
	 * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
	 * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
	 * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
	 * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
	 *								</tt></td></tr>
	 * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
	 *								</tt></td></tr>
	 * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
	 * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
	 * </table>
	 * 
	 * @param	syscall	the syscall number.
	 * @param	a0	the first syscall argument.
	 * @param	a1	the second syscall argument.
	 * @param	a2	the third syscall argument.
	 * @param	a3	the fourth syscall argument.
	 * @return	the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();


		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by
	 * <tt>UserKernel.exceptionHandler()</tt>. The
	 * <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 *
	 * @param	cause	the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3)
					);
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;				       

		default:
			Lib.debug(dbgProcess, "Unexpected exception: " +
					Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}
}