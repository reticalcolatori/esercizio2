package com;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MultiplePutClient {

	private static final int SOCKET_CONN_ERR = 1; // Connessione non andata a buon fine.
	private static final int FILE_NOT_EXISTS_ERR = 2; // File non trovato
	private static final int IO_ERR = 3; // Errore IO
	private static final int ARGS_ERR = 4; // Errore negli argomenti

	// Invocazione client:
	// MultiplePutClient serverAddress serverPort dirname dimSoglia

	// Il client trasferisce solo se il file supera una dimensione minima.
	// Protocollo richiesta put file:
	// 1) invio nomefile

	// 2) In ricezione ho una conferma: se positiva posso trasferire il file.
	// L'esito è positivo solo se nel direttorio corrente del server non è presente
	// un file con nome uguale.
	// Il server rimane sullo stesso direttorio.

	// 3)Invio la lunghezza
	// lunghezza(long)

	// 4)Il server invia un ACK

	// 5)Quando ho finito di inviare tutti i file (anche 0) faccio la shutdown di
	// output.
	// 6)Il server invierà una conferma.

	// Risposte dal server
	private static final String RESULT_ATTIVA = "attiva";
	private static final String RESULT_SALTA_FILE = "salta file";
	private static final String RESULT_OK = "OK";

	private static boolean isPortValid(int port) {
		return 1024 < port && port < 0x10000;
	}

	// Variabili per il direttorio.
	private final String dirname;
	private final File dirFile;
	private final Path dirPath;
	private final int dimensioneSoglia;

	private final InetAddress serverAddress;
	private final int serverPort;

	public MultiplePutClient(String serverAddress, int serverPort, String dirname, int dimensioneSoglia)
			throws IllegalArgumentException, IOException {
		this(InetAddress.getByName(serverAddress), serverPort, dirname, dimensioneSoglia);
	}

	public MultiplePutClient(InetAddress serverAddress, int serverPort, String dirname, int dimensioneSoglia)
			throws IllegalArgumentException, IOException {
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;

		// Controllo porta.
		if (!isPortValid(serverPort))
			throw new IllegalArgumentException("server port non valida");

		this.dirname = dirname;
		this.dimensioneSoglia = dimensioneSoglia;

		if (dimensioneSoglia < 0) //controllo soglia
			throw new IllegalArgumentException("dimensioneSoglia non valida (<0)");

		this.dirFile = new File(dirname);
		this.dirPath = Paths.get(dirFile.toURI());

		// Controllo directory.
		if (!dirFile.isDirectory())
			throw new IOException("Directory non valida");
	}

	public void esegui() {
		// Apro la directory e recupero un array dei files
		File[] files = dirFile.listFiles();

		// Directory vuota non devo fare nulla.
		if (files.length == 0) {
			System.out.println("Directory vuota, non eseguo traferimenti.");
			return;
		}

		//preparo strutture input/output
		Socket socket = null;
		BufferedInputStream socketIn = null;
		BufferedOutputStream socketOut = null;
		DataInputStream socketDataIn = null;
		DataOutputStream socketDataOut = null;
		String risposta = null;

		// Apro la connessione
		try {
			socket = new Socket(serverAddress, serverPort);
			socketIn = new BufferedInputStream(socket.getInputStream());
			socketOut = new BufferedOutputStream(socket.getOutputStream());

			socketDataIn = new DataInputStream(socketIn);
			socketDataOut = new DataOutputStream(socketOut);
		} catch (IOException e) {
			// se ho IOExeption devo terminare
			e.printStackTrace();
			System.exit(SOCKET_CONN_ERR);
		}

		for (File file : files) {
			// Per ogni file verifico se la dimensione supera la soglia minima
			long dimFile = file.length();

			if (dimFile >= dimensioneSoglia) {
				// Posso inviare la richesta
				try {
					socketDataOut.writeUTF(file.getName());
				} catch (IOException e) {
					// Non riesco a inviare la richiesta continuo alla prossima.
					e.printStackTrace();
					continue;
				}

				// Ricevo risposta
				try {
					risposta = socketDataIn.readUTF();
				} catch (IOException e) {
					// Errore lettura salto
					e.printStackTrace();
					continue;
				}

				// Decodifico la risposta
				if (RESULT_ATTIVA.equalsIgnoreCase(risposta)) {

					// Invio lunghezza file
					try {
						socketDataOut.writeLong(dimFile);
					} catch (IOException e) {
						// Perché esco?
						// Il server, non ricevendo la lunghezza del file, andrà in attesa fino a timeout
						e.printStackTrace();
						System.exit(IO_ERR);
					}

					// Posso inviare il file.
					try (InputStreamReader inFile = new FileReader(file)) {
						int tmpByte;
						while ((tmpByte = inFile.read()) >= 0)
							socketOut.write(tmpByte);

					} catch (FileNotFoundException ex) {
						// Impossibile già verificato. Lo togliamo?
						System.exit(FILE_NOT_EXISTS_ERR);
					} catch (IOException ex) {
						// Perché esco?
						// Il server, non ricevendo tutti i byte del file, andrà in attesa fino a timeout
						ex.printStackTrace();
						System.exit(IO_ERR);
					}

					// Ricevo ACK da server.
					try {
						risposta = socketDataIn.readUTF();
					} catch (IOException e) {
						// Errore lettura salto
						e.printStackTrace();
						continue;
					}

					// La nostra non è solo una scelta (furba tra l'altro), ma è proprio
					// una specifica: "Il Multiple put viene effettuato file
					// per file con assenso del server per ogni file" !!
					if (RESULT_OK.equalsIgnoreCase(risposta)) {
						System.out.println("File " + file.getName() + " caricato.");
					} else {
						System.out.println("Errore nell'invio " + file.getName() + ": " + risposta);
					}

				}
			}
		}

		// Ho finito di inviare i file: chiudo la connessione.
		try {
			socket.shutdownOutput(); //chiudo out
			System.out.println(socketDataIn.readUTF());
			socket.shutdownInput(); //chiudo in
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(IO_ERR);
		}

	}

	public static void main(String[] args) {
		// Invocazione: MultiplePutClient serverAddress serverPort dirname dimSoglia

		// Controllo preliminare errori: se qualcosa non va non creo e avvio nemmeno il client!
		if(args.length != 4) {
			System.err.println("usage java MultiplePutClient serverAddress serverPort dirname dimSoglia");
			System.exit(ARGS_ERR);
		}

		MultiplePutClient client = null;

		// Utilizzo direttamente l'array args per evitare stutture inutili
		try {
			client = new MultiplePutClient(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]));
		} catch (IllegalArgumentException | IOException e) {
			// se qualcosa va storto esco perchè gli argomenti passati non sono corretti
			e.printStackTrace();
			System.exit(ARGS_ERR);
		}

		//Posso avviare il client
		client.esegui();

		// SIAMO PROPRIO SICURI DI VOLER INCAPSULARE LA LOGICA DENTRO IL METODO ESEGUI()?
		// IL PROF CI HA CRITICATO IL FATTO DI NON USARE FUNZIONI ESTERNE AL MAIN A MENO CHE
		// NON VENGANO SICURAMENTE CHIAMATE PIU' VOLTE!
	}
	
	
}
