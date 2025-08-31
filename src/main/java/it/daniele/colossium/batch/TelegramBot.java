package it.daniele.colossium.batch;

import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.SearchCriteria;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.repository.RicercheRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import secrets.ConstantColossium;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static it.daniele.colossium.domain.SearchCriteria.DATA_DEFAULT_MAX;
import static it.daniele.colossium.domain.SearchCriteria.DATA_DEFAULT_MIN;
import static it.daniele.colossium.domain.SearchCriteria.LIMIT_DEFAULT;


@SuppressWarnings("deprecation")
@Configuration
public class TelegramBot extends TelegramLongPollingBot {

    public static final String TOKEN = "#@@#";
    public static final String TOKEN_ANNO = "#ANNO#";
    public static final String TOKEN_DATA = "#DATA#";
    public static final String TOKEN_MESE = "#MESE#";
    public static final String TOKEN_GIORNO = "#GIORNO#";
    public static final String TOKEN_CANCELLA = "#CANCELLA#";
    public static final String TOKEN_RICERCA = "#RICERCA#";

    TelegramBot() {
        super(ConstantColossium.BOT_TOKEN);
    }

    Logger logger = LoggerFactory.getLogger(this.getClass());


    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RicercheRepository ricercheRepository;

    @Autowired
    ScheduledConfig scheduledConfig;

    @Override
    public String getBotUsername() {
        return ConstantColossium.BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return ConstantColossium.BOT_TOKEN;
    }

    private void salvaMessaggio(Message message) {
		/*
		con entitymanager errore perchè manca contesto transazionale			
		TelegramMsg tm = new TelegramMsg(message.getMessageId(), LocalDateTime.now());
		entityManager.persist(tm);
		 */
        jdbcTemplate.update("insert into telegram_msg (id,data_consegna) values (?,?)", message.getMessageId(), LocalDateTime.now());
    }

    public void inviaMessaggio(String msg) {
        if (msg != null && !msg.isEmpty()) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.enableHtml(true);
            sendMessage.setParseMode("html");
            sendMessage.setChatId(ConstantColossium.MY_CHAT_ID);
            sendMessage.setText(msg);
            try {
                Message message = execute(sendMessage);
                salvaMessaggio(message);
            } catch (TelegramApiRequestException e) {
                if (e.getErrorCode() == 429) {
                    int retryAfterSeconds = e.getParameters().getRetryAfter();
                    // Attendi per il periodo specificato prima di ritentare la richiesta
                    try {
                        Thread.sleep(retryAfterSeconds * 1000L);
                    } catch (Exception e2) {
                        throw new RuntimeException(e2);
                    }
                    inviaMessaggio(msg);
                } else {
                    throw new RuntimeException(e);
                }
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void sendImageToChat(String imageUrl, String msg) {
        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(ConstantColossium.MY_CHAT_ID);
        sendPhoto.setPhoto(new InputFile(imageUrl));
        sendPhoto.setCaption(msg);
        try {
            Message message = execute(sendPhoto);
            salvaMessaggio(message);
        } catch (TelegramApiRequestException e) {
            if (e.getErrorCode() == 429) {
                int retryAfterSeconds = e.getParameters().getRetryAfter();
                // Attendi per il periodo specificato prima di ritentare la richiesta
                try {
                    Thread.sleep(retryAfterSeconds * 1000L);
                } catch (Exception e2) {
                    throw new RuntimeException(e2);
                }
                sendImageToChat(imageUrl, msg);
            } else {
                try {
                    sendPhoto.setPhoto(new InputFile("https://www.teatrocolosseo.it/images/throbber.gif"));
                    Message message = execute(sendPhoto);
                    salvaMessaggio(message);
                } catch (TelegramApiException e2) {
                    inviaMessaggio("**** NO IMG *** \n\r" + msg);
                }
            }
        } catch (TelegramApiException e) {
            try {
                sendPhoto.setPhoto(new InputFile("https://www.teatrocolosseo.it/images/throbber.gif"));
                Message message = execute(sendPhoto);
                salvaMessaggio(message);
            } catch (TelegramApiException e2) {
                inviaMessaggio("**** NO IMG *** \n\r" + msg);
            }
        }
    }


    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleMessage(Message message) throws Exception {
        Long chatId = message.getChat().getId();

        String testo = message.getText();
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());

        if (testo.equals("/componi")) {
            userState.remove(chatId);
            userCriteria.remove(chatId);
            userMessageIdForDelete.remove(chatId);
            Message msgComponi = execute(sendInlineKeyBoard(chatId, testo, TipoKeyboard.FILTRI));
            userMessageIdOld.put(chatId, msgComponi.getMessageId());
        } else if (testo.equals("/job")) {
            scheduledConfig.runBatchJob();

        } else if (testo.equals("/ricerca")) {
            ricerca(chatId);
//		AGGIUNGE TASTIERA
            execute(altraTastiera(chatId));
        } else {
            String stato = userState.get(chatId);
            if (stato != null) {
                SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(stato);
                boolean isChanged = false;
                boolean cancellare = true;
                List<Integer> msgForDelete = userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>());
                switch (filtroRicerca) {
                    case TESTO:
                        msgForDelete.add(message.getMessageId());
                        if (!testo.equals(criteria.getTesto())) {
                            criteria.setTesto(testo);
                            isChanged = true;
                        }
                        userState.put(chatId, null);
                        break;
                    case LIMIT:
                        try {
                            msgForDelete.add(message.getMessageId());
                            Integer iTesto = Integer.parseInt(testo);
                            if (!iTesto.equals(criteria.getLimit())) {
                                criteria.setLimit(iTesto);
                                isChanged = true;
                                userState.put(chatId, null);
                            }
                        } catch (NumberFormatException e) {
                            cancellare = false;
                            Message msgValoreNumerico = execute(creaSendMessage(chatId, "Inserire un valore numerico "));
                            msgForDelete.add(msgValoreNumerico.getMessageId());
                        }
                        break;
                    default:
                        throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                }
                userCriteria.put(chatId, criteria);
                if (isChanged) {
                    aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria));
                }
                if (cancellare) {
                    cancellaMessaggi(chatId);
                }
            }
        }
    }

    private void ricerca(Long chatId) throws TelegramApiException {
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());
        execute(creaSendMessage(chatId, "Avvio ricerca con criteri: " + criteria));
        List<Show> shows = new ArrayList<>();
        if (ObjectUtils.isEmpty(criteria.getFonte())
                || !criteria.getFonte().equals(Fonti.COLOSSEO_NEWS.name())) {
            shows = ricercheRepository.cercaShow(criteria);
        }
        List<News> news = new ArrayList<>();
        if (!ObjectUtils.isEmpty(criteria.getFonte())
                && criteria.getFonte().equals(Fonti.COLOSSEO_NEWS.name())) {
            news = ricercheRepository.cercaNews(criteria);
        }
        int tot = shows.size() + news.size();
        if (tot > criteria.getLimit()) {
            execute(creaSendMessage(chatId, "Restringere la ricerca. Troppi elementi: " + tot));
        } else if (tot == 0) {
            execute(creaSendMessage(chatId, "Nessun elemento trovato "));
        } else {
            for (Show show : shows) {
                sendImageToChat(show.getImg(), show + "\n[ consegnato il: " + show.getDataConsegna() + " ]");
            }
            for (News el : news) {
                inviaMessaggio(el.toString() + "\n[ consegnato il: " + el.getDataConsegna() + " ]");
            }
        }
        Message msgFiltri = execute(sendInlineKeyBoard(chatId, "Componi e ricerca", TipoKeyboard.FILTRI));
        userMessageIdOld.put(chatId, msgFiltri.getMessageId());
    }

    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        Long chatId = callback.getMessage().getChatId();
        Integer messageId = callback.getMessage().getMessageId();
        String data = callback.getData();
        String stato = userState.get(chatId);
        SearchCriteria criteria = userCriteria.get(chatId);
        if (criteria == null) {
            criteria = new SearchCriteria();
            userCriteria.put(chatId, criteria);
        }
        if (stato == null) {
            if (data.startsWith(TOKEN_CANCELLA)) {
                boolean isChanged = false;
                data = data.substring(TOKEN_CANCELLA.length());
                SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(data);
                switch (filtroRicerca) {
                    case TESTO:
                        if (!ObjectUtils.isEmpty(criteria.getTesto())) {
                            isChanged = true;
                            criteria.setTesto(null);
                        }
                        break;
                    case FONTE:
                        if (!ObjectUtils.isEmpty(criteria.getFonte())) {
                            isChanged = true;
                            criteria.setFonte(null);
                        }
                        break;
                    case DATA_MIN:
                        if (!criteria.getDataMin().equals(DATA_DEFAULT_MIN)) {
                            isChanged = true;
                            criteria.setDataMin(DATA_DEFAULT_MIN);
                        }
                        break;
                    case DATA_MAX:
                        if (!criteria.getDataMax().equals(DATA_DEFAULT_MAX)) {
                            isChanged = true;
                            criteria.setDataMax(DATA_DEFAULT_MAX);
                        }
                        break;
                    case DATA_CONSEGNA_MIN:
                        if (!criteria.getDataConsegnaMin().equals(DATA_DEFAULT_MIN)) {
                            isChanged = true;
                            criteria.setDataConsegnaMin(DATA_DEFAULT_MIN);
                        }
                        break;
                    case DATA_CONSEGNA_MAX:
                        if (!criteria.getDataConsegnaMax().equals(DATA_DEFAULT_MAX)) {
                            isChanged = true;
                            criteria.setDataConsegnaMax(DATA_DEFAULT_MAX);
                        }
                        break;
                    case LIMIT:
                        if (!criteria.getLimit().equals(LIMIT_DEFAULT)) {
                            isChanged = true;
                            criteria.setLimit(LIMIT_DEFAULT);
                        }
                        break;
                    default:
                        throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                }
                userCriteria.put(chatId, criteria);
                userState.put(chatId, null);
                if (isChanged) {
                    aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria())));
                }
            } else {
                if (data.equals(TOKEN_RICERCA)) {
                    ricerca(chatId);
                } else if (data.startsWith(TOKEN_ANNO)) {
                    String tipoRicerca = data.substring(TOKEN_ANNO.length());
                    Message msgAnni = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.ANNI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgAnni.getMessageId());
                } else if (data.startsWith(TOKEN_MESE)) {
                    String tipoRicerca = data.substring(TOKEN_MESE.length());
                    Message msgMesi = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.MESI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgMesi.getMessageId());
                } else if (data.startsWith(TOKEN_GIORNO)) {
                    String tipoRicerca = data.substring(TOKEN_GIORNO.length());
                    Message msgGiorni = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.GIORNI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgGiorni.getMessageId());
                } else if (data.startsWith(TOKEN_DATA)) {
                    data = data.substring(TOKEN_DATA.length());
                    if (data.startsWith(TOKEN_ANNO)) {
                        updateData(data, chatId, TOKEN_ANNO);
                    } else if (data.startsWith(TOKEN_MESE)) {
                        updateData(data, chatId, TOKEN_MESE);
                    } else if (data.startsWith(TOKEN_GIORNO)) {
                        updateData(data, chatId, TOKEN_GIORNO);
                    } else {
                        throw new RuntimeException("Stato inconsistente");
                    }

                } else {
                    SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(data);
                    userState.put(chatId, filtroRicerca.name());
                    String now = LocalDate.now().format(FORMATTER_SIMPLE);
                    switch (filtroRicerca) {
                        case TESTO:
                            //  userMessageIdOld.put(chatId, messageId);
                            Message msgTestDaCercare = execute(creaSendMessage(chatId, "Inserisci il testo da cercare:"));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgTestDaCercare.getMessageId());

                            break;
                        case FONTE:
                            Message msgFonti = execute(sendInlineKeyBoard(chatId, "Fonti", TipoKeyboard.FONTI));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgFonti.getMessageId());
                            break;
                        case LIMIT:
                            //userMessageIdOld.put(chatId, messageId);
                            Message msgNuovoLimite = execute(creaSendMessage(chatId, "Inserisci il nuovo limite:"));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgNuovoLimite.getMessageId());
                            break;
                        case DATA_MIN:
                            userState.put(chatId, null);
                            if (criteria.getDataMin().compareTo(now) != 0) {
                                criteria.setDataMin(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria())));
                            }
                            break;
                        case DATA_MAX:
                            userState.put(chatId, null);
                            if (criteria.getDataMax().compareTo(now) != 0) {
                                criteria.setDataMax(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria())));
                            }
                            break;
                        case DATA_CONSEGNA_MIN:
                            userState.put(chatId, null);
                            if (criteria.getDataConsegnaMin().compareTo(now) != 0) {
                                criteria.setDataConsegnaMin(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria())));
                            }
                            break;
                        case DATA_CONSEGNA_MAX:
                            userState.put(chatId, null);
                            if (criteria.getDataConsegnaMax().compareTo(now) != 0) {
                                criteria.setDataConsegnaMax(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria())));
                            }
                            break;
                        default:
                            throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                    }
                }
            }
        } else if (stato.equals(SearchCriteria.FiltriRicerca.FONTE.name())) {
            if (!data.equals(criteria.getFonte())) {
                criteria.setFonte(data);
                aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria));
                userCriteria.put(chatId, criteria);
            }
            cancellaMessaggi(chatId);
            userState.put(chatId, null);
        } else {
            throw new RuntimeException("Situazione inconsistente: " + stato);
        }
    }

    private void updateData(String data, Long chatId, String tokenElemData) throws TelegramApiException {
        data = data.substring(tokenElemData.length());
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());
        String[] split = data.split(TOKEN);
        SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(split[0]);
        String extractCriteria = extractCriteria(criteria, filtroRicerca);
        LocalDate date = LocalDate.parse(extractCriteria, FORMATTER_SIMPLE);
        switch (tokenElemData) {
            case TOKEN_ANNO:
                date = date.withYear(Integer.parseInt(split[1]));
                break;
            case TOKEN_MESE:
                date = date.withMonth(Integer.parseInt(split[1]));
                break;
            case TOKEN_GIORNO:
                date = date.withDayOfMonth(Integer.parseInt(split[1]));
                break;
            default:
                throw new RuntimeException("Situazione inconsistente");
        }
        String newDate = date.format(FORMATTER_SIMPLE);
        boolean isChanged = false;
        switch (filtroRicerca) {
            case DATA_MIN:
                if (!criteria.getDataMin().equals(newDate)) {
                    criteria.setDataMin(newDate);
                    isChanged = true;
                }
                break;
            case DATA_MAX:
                if (!criteria.getDataMax().equals(newDate)) {
                    criteria.setDataMax(newDate);
                    isChanged = true;
                }
                break;
            case DATA_CONSEGNA_MIN:
                if (!criteria.getDataConsegnaMin().equals(newDate)) {
                    criteria.setDataConsegnaMin(newDate);
                    isChanged = true;
                }
                break;
            case DATA_CONSEGNA_MAX:
                if (!criteria.getDataConsegnaMax().equals(newDate)) {
                    criteria.setDataConsegnaMax(newDate);
                    isChanged = true;
                }
                break;
            default:
                throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
        }
        userCriteria.put(chatId, criteria);
        userState.put(chatId, null);
        if (isChanged) {
            aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria));
        }
        cancellaMessaggi(chatId);

    }

    private SendMessage creaSendMessage(long chatId, String msg) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.enableHtml(true);
        sendMessage.setParseMode("html");
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(msg);
        return sendMessage;
    }

    private void cancellaMessaggi(long chatId) {
        userMessageIdForDelete.getOrDefault(chatId, new ArrayList<>()).forEach(el -> {
            DeleteMessage deleteMessage = new DeleteMessage(String.valueOf(chatId), el);
            try {
                execute(deleteMessage);
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        });
        userMessageIdForDelete.remove(chatId);

    }

    private void aggiornaTastiera(long chatId, int messageId, List<List<InlineKeyboardButton>> nuovaTastiera) throws TelegramApiException {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        inlineKeyboardMarkup.setKeyboard(nuovaTastiera);

        EditMessageReplyMarkup editMessageReplyMarkup = new EditMessageReplyMarkup();
        editMessageReplyMarkup.setChatId(String.valueOf(chatId));
        editMessageReplyMarkup.setMessageId(messageId);
        editMessageReplyMarkup.setReplyMarkup(inlineKeyboardMarkup);
        execute(editMessageReplyMarkup);
    }

    private SendMessage sendInlineKeyBoard(long chatId, String testo, TipoKeyboard tipoKeyboard, String... info) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboards;
        switch (tipoKeyboard) {
            case FILTRI:
                keyboards = generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()));
                break;
            case FONTI:
                keyboards = generaElencoFonti();
                break;
            case ANNI:
                keyboards = generaAnni(info[0]);
                break;
            case MESI:
                keyboards = generaMesi(info[0]);
                break;
            case GIORNI:
                keyboards = generaGiorni(info[0]);
                break;
            default:
                throw new RuntimeException("Tipi Keyboard non gestito: " + tipoKeyboard);
        }


        SendMessage sendMessage = new SendMessage();
        sendMessage.enableHtml(true);
        sendMessage.setParseMode("html");
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(testo);
        inlineKeyboardMarkup.setKeyboard(keyboards);
        sendMessage.setReplyMarkup(inlineKeyboardMarkup);
        return sendMessage;
    }

    private List<List<InlineKeyboardButton>> generaElencoFiltri(SearchCriteria criteria) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            for (SearchCriteria.FiltriRicerca filtroRicerca : SearchCriteria.FiltriRicerca.values()) {
                List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
                String extractCriteria = extractCriteria(criteria, filtroRicerca);
                if (filtroRicerca == SearchCriteria.FiltriRicerca.DATA_MIN
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_MAX
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_CONSEGNA_MIN
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_CONSEGNA_MAX) {
                    LocalDate date = LocalDate.parse(extractCriteria, FORMATTER_SIMPLE);
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    //TITOLO
                    inlineKeyboardButton.setText(filtroRicerca.getDes());
                    inlineKeyboardButton.setCallbackData(filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                    //ANNO
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(String.valueOf(date.getYear()));
                    inlineKeyboardButton.setCallbackData(TOKEN_ANNO + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    //MESE
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(String.valueOf(date.getMonth()));
                    inlineKeyboardButton.setCallbackData(TOKEN_MESE + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    inlineKeyboardButton = new InlineKeyboardButton();
                    //GIORNO
                    inlineKeyboardButton.setText(String.valueOf(date.getDayOfMonth()));
                    inlineKeyboardButton.setCallbackData(TOKEN_GIORNO + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                } else {
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(filtroRicerca.getDes() + ": " + extractCriteria);
                    inlineKeyboardButton.setCallbackData(filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("\uD83D\uDDD1");
                    inlineKeyboardButton.setCallbackData(TOKEN_CANCELLA + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                }
            }

            List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
            InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
            inlineKeyboardButton.setText("RICERCA");
            inlineKeyboardButton.setCallbackData(TOKEN_RICERCA);
            keyboardButtonsRow1.add(inlineKeyboardButton);
            righe.add(keyboardButtonsRow1);


            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaElencoFonti() {
        try {
            List<List<InlineKeyboardButton>> rowList = new ArrayList<>();
            for (Fonti fonte : Fonti.values()) {
                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(fonte.name());
                inlineKeyboardButton.setCallbackData(fonte.name());
                keyboardButtonsRow1.add(inlineKeyboardButton);
                rowList.add(keyboardButtonsRow1);
            }
            return rowList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaAnni(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 2021; i <= 2030; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(String.valueOf(i));
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_ANNO + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 5) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaMesi(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(LocalDate.of(2021, i, 1).getMonth().name());
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_MESE + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 4) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaGiorni(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 1; i <= 31; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(String.valueOf(i));
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_GIORNO + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 6) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String extractCriteria(SearchCriteria criteria, SearchCriteria.FiltriRicerca filtroRicerca) {
        String ret;
        switch (filtroRicerca) {
            case TESTO:
                ret = criteria.getTesto();
                break;
            case FONTE:
                ret = criteria.getFonte();
                break;
            case DATA_MIN:
                ret = criteria.getDataMin();
                break;
            case DATA_MAX:
                ret = criteria.getDataMax();
                break;
            case DATA_CONSEGNA_MIN:
                ret = criteria.getDataConsegnaMin();
                break;
            case DATA_CONSEGNA_MAX:
                ret = criteria.getDataConsegnaMax();
                break;
            case LIMIT:
                ret = String.valueOf(criteria.getLimit());
                break;
            default:
                throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
        }
        return ret == null ? "" : ret;
    }

    private SendMessage altraTastiera(long chatId)  {
        SendMessage sendMessage = new SendMessage();
        sendMessage.enableHtml(true);
        sendMessage.setParseMode("html");
        sendMessage.enableMarkdown(true);

        sendMessage.setChatId(Long.toString(chatId));
        // Create a keyboard
        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        sendMessage.setReplyMarkup(replyKeyboardMarkup);
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);
        replyKeyboardMarkup.setIsPersistent(true);

        // Create a list of keyboard rows
        List<KeyboardRow> keyboard = new ArrayList<>();

        Set<String> elencoGiocatori = Set.of("ciao", "come", "va");
        for (String giocatore : elencoGiocatori) {
            KeyboardRow kbRiga = new KeyboardRow();
            KeyboardButton kbGiocatore = new KeyboardButton(giocatore);
            kbRiga.add(kbGiocatore);
            keyboard.add(kbRiga);
        }


        // and assign this list to our keyboard
        replyKeyboardMarkup.setKeyboard(keyboard);
        sendMessage.setReplyMarkup(replyKeyboardMarkup);
        sendMessage.setText("seleziona il giocatore");
        return sendMessage;
    }


    @PostConstruct
    public TelegramBot inizializza() throws Exception {
        logger.info("Bot Avviato");
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        jobConfigBot = this;
        registerBot = telegramBotsApi.registerBot(jobConfigBot);
        return jobConfigBot;
    }


    TelegramBot jobConfigBot;
    private BotSession registerBot;


    private final Map<Long, String> userState = new HashMap<>();
    private final Map<Long, Integer> userMessageIdOld = new HashMap<>();
    private final Map<Long, List<Integer>> userMessageIdForDelete = new HashMap<>();
    private final Map<Long, SearchCriteria> userCriteria = new HashMap<>();

    public enum TipoKeyboard {FILTRI, FONTI, ANNI, MESI, GIORNI}

    public enum Fonti {CONCORDIA, DICE, TICKETONE, TICKETMASTER, VIVATICKET, MAILTICKET, COLOSSEO, COLOSSEO_NEWS}

    DateTimeFormatter FORMATTER_SIMPLE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

}
