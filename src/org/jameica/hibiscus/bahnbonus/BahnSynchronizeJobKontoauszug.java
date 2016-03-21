package org.jameica.hibiscus.bahnbonus;

import java.rmi.RemoteException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.annotation.Resource;

import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.ThreadedRefreshHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlTable;
import com.gargoylesoftware.htmlunit.html.HtmlTableRow;

import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Implementierung des Kontoauszugsabruf fuer AirPlus.
 * Von der passenden Job-Klasse ableiten, damit der Job gefunden wird.
 */
public class BahnSynchronizeJobKontoauszug extends SynchronizeJobKontoauszug implements BahnSynchronizeJob
{
	private final static I18N i18n = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getI18N();

	@Resource
	private BahnSynchronizeBackend backend = null;

	private DateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN);
	/**
	 * @see org.jameica.hibiscus.barclaystg.AirPlusSynchronizeJob#execute()
	 */
	@Override
	public void execute() throws Exception
	{
		Konto konto = (Konto) this.getContext(CTX_ENTITY); // wurde von AirPlusSynchronizeJobProviderKontoauszug dort abgelegt

		Logger.info("Rufe Umsä�tze ab f�r " + backend.getName());

		////////////////
		String username = konto.getKundennummer();
		String password = konto.getMeta(BahnSynchronizeBackend.PROP_PASSWORD, null);
		if (username == null || username.length() == 0)
			throw new ApplicationException(i18n.tr("Bitte geben Sie Ihren Benutzernamen im Feld Kundenkennung ein."));

		if (password == null || password.length() == 0)
			password = Application.getCallback().askPassword("Password für bahn.bonus");

		Logger.info("username: " + username);
		////////////////


		List<Umsatz> fetched = doOneAccount(konto, username, password);

		Date oldest = null;

		// Ermitteln des aeltesten abgerufenen Umsatzes, um den Bereich zu ermitteln,
		// gegen den wir aus der Datenbank abgleichen
		for (Umsatz umsatz:fetched)
		{
			if (oldest == null || umsatz.getDatum().before(oldest))
				oldest = umsatz.getDatum();
		}

		boolean neueUmsaetze = false;

		// Wir holen uns die Umsaetze seit dem letzen Abruf von der Datenbank
		GenericIterator existing = konto.getUmsaetze(oldest,null);
		for (Umsatz umsatz:fetched)
		{
			if (existing.contains(umsatz) != null)
				continue; // haben wir schon

			neueUmsaetze = true;

			// Neuer Umsatz. Anlegen
			umsatz.store();

			// Per Messaging Bescheid geben, dass es einen neuen Umsatz gibt. Der wird dann sofort in der Liste angezeigt
			Application.getMessagingFactory().sendMessage(new ImportMessage(umsatz));
		}


		if (neueUmsaetze) {
			// Für alle Buchungen rückwirkend den Saldo anpassen, da Lufthansa ab und zu auch mal Korrekturen mit 
			// dem ursprünglichen Datum einfügt

			double saldo = konto.getSaldo();
			existing.begin();
			while (existing.hasNext()) {
				Umsatz a = (Umsatz) existing.next();
				a.setSaldo(saldo);
				a.store();
				saldo -= a.getBetrag();
			}
		}
		konto.store();
		
		// Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat
		Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
	}

	public List<Umsatz> doOneAccount(Konto konto, String username, String password) throws Exception {
		List<Umsatz> umsaetze = new ArrayList<Umsatz>();

		final WebClient webClient = new WebClient();
		webClient.setCssErrorHandler(new SilentCssErrorHandler());
		webClient.setRefreshHandler(new ThreadedRefreshHandler());

		// Login-Page und Login
		HtmlPage page = webClient.getPage("https://fahrkarten.bahn.de/privatkunde/start/start.post?lang=de&scope=login");
		((HtmlInput) page.getElementById("username")).setValueAttribute(username);
		((HtmlInput) page.getElementById("password")).setValueAttribute(password);
		HtmlButton button = (HtmlButton) page.getElementById("button.weiter");

		page = button.click();
		webClient.waitForBackgroundJavaScript(3000);
		page = ((HtmlInput) page.getElementById("mbahnbonuspunkte.button.bahnbonus")).click();

		Date now = new Date();
		((HtmlInput) page.getElementById("auswahl.von")).setValueAttribute(now.getDate() + "." +  (now.getMonth() + 1) + "." + (now.getYear() + 1900 - 3));
		button = (HtmlButton) page.getElementById("button.aktualisieren");
		if (button == null) {
			throw new ApplicationException(i18n.tr("Button zur Aktualisierung nicht gefunden!"));
		}
		page = button.click();


		List<HtmlTable> tables = (List<HtmlTable>) page.getByXPath( "//table[contains(@class, 'bcpunktedetails')]");
		if (tables.size() != 1) {
			throw new ApplicationException("Table nicht gefunden! Size: " + tables.size());
		}
		HtmlTable table = (HtmlTable) tables.get(0);
		for (int i = 1; i < table.getRows().size(); i++) {
			HtmlTableRow row = table.getRow(i);
			if (row.getCells().size() == 1 || row.getCell(0).asText().isEmpty()) {
				continue;
			}
			String text = row.getCell(3).asText() + " " + row.getCell(4).asText() + " " + row.getCell(7).asText();
			if (!row.getCell(6).asText().isEmpty()) {
				text += " Statuspunkte: " + row.getCell(6).asText(); 
			}
			store(row.getCell(2).asText(), text, row.getCell(5).asText(), umsaetze, konto);
		}

		extractPunkteStand(page, konto);
		
		webClient.closeAllWindows();
		return umsaetze;
	}

	private void extractPunkteStand(HtmlPage page, Konto konto) throws ApplicationException {
		List<HtmlDivision> punkte = (List<HtmlDivision>) page.getByXPath( "//div[contains(@class, 'bcpunkteinfo')]");
		if (punkte.size() != 1) {
			throw new ApplicationException("Punkteübersicht nicht gefunden! Size: " + punkte.size());
		}
		// No better way :-(
		HtmlDivision div = (HtmlDivision) punkte.get(0).getChildNodes().get(0).getChildNodes().get(1).getChildNodes().get(1);
		try {
			konto.setSaldo(string2float(div.asText().trim()));
		} catch (RemoteException e) {
			throw new ApplicationException("Punkte den Punktestand nicht ermitteln: " + div.asText().trim());
		}

	}




	private void store(String date, String text, String punkte, List<Umsatz> umsaetze, Konto konto) throws RemoteException, ParseException {
		Umsatz newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
		newUmsatz.setKonto(konto);
		newUmsatz.setBetrag(string2float(punkte));
		newUmsatz.setDatum(df.parse(date));
		newUmsatz.setValuta(df.parse(date));
		newUmsatz.setWeitereVerwendungszwecke(Utils.parse(text));
		umsaetze.add(newUmsatz);
	}

	/**
	 * - Tausender Punkte entfernen
	 * - Komma durch Punkt ersetzen
	 * @param s
	 * @return
	 */
	public static float string2float(String s) {
		try {
			return Float.parseFloat(s.replace(".", "").replace(",", "."));
		} catch (Exception e) {
			throw new RuntimeException("Cannot convert " + s + " to float");
		}

	}

}




