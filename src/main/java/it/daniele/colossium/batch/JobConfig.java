package it.daniele.colossium.batch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.domain.TelegramMsg;
import secrets.ConstantColossium;


@Configuration
@EnableBatchProcessing
public class JobConfig extends TelegramLongPollingBot {




	@Autowired
	StepBuilderFactory stepBuilderFactory;

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	@Qualifier("readerNews")
	ItemReader<News> itemReaderNews;

	@Autowired
	@Qualifier("readerShow")
	ItemReader<Show> itemReaderShow;
	
	@Autowired
	@Qualifier("processorShow")
	ItemProcessor<Show,Show> itemProcessorShow;

	@Autowired
	@Qualifier("processorNews")
	ItemProcessor<News,News> itemProcessorNews;
	
	@Autowired
	@Qualifier("writerNews")
	ItemWriter<News> itemWriterNews;

	@Autowired
	@Qualifier("writerShow")
	ItemWriter<Show> itemWriterShow;
	
	@PersistenceContext
	EntityManager entityManager;




	@Autowired
	JobBuilderFactory jobBuilderFactory;

	@Autowired
	@Qualifier("stepNews")
	Step stepBeanNews;

	@Autowired
	@Qualifier("stepShow")
	Step stepBeanShow;
	
	@PostConstruct
	private void postConstructor() {
		String response = restTemplate.getForObject("https://www.teatrocolosseo.it/News/Default.aspx", String.class);
		Document doc = Jsoup.parse(response);
		for (int i=0;i<ConstantColossium.MAX_NEWS;i++) {
			String textDes = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblDescrizione1]").text();
			String textData = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblData]").text();
			String textTitolo = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblTitolo]").text();
			News news = new News(textData, textTitolo, textDes);
			listNews.add(news);
		}
		response = restTemplate.getForObject("https://www.teatrocolosseo.it/Stagione.aspx", String.class);
		doc = Jsoup.parse(response);
		Elements select = doc.select(".boxspet");
		for (int i=0;i<select.size();i++) {
			Element element = select.get(i);
			try {
				String id = element.select(".spett-box-image").first().attr("id").replace("_imageBox", "");
				String data = doc.select("span[id*=" + id + "_lblDataIntro]").text();
				String titolo = doc.select("#"+ id +"_divOverlay1 strong").first().text();
				String img = "https://www.teatrocolosseo.it/" + element.select(".spett-box-image").first().attr("data-source");
				String href="https://www.teatrocolosseo.it/" + doc.select("a[id*=" + id + "_hlCompra]").first().attr("href");
				Show show = new Show(data, titolo, img, href);
				listShow.add(show);
			}
			catch (Exception e) {
			}
		}
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

	private void init() {
		inviaMessaggio("Cerco le news...");

		List<TelegramMsg> resultList = entityManager.createQuery("select t from TelegramMsg t where dataEliminazione is null", TelegramMsg.class).getResultList();
		resultList.forEach(el-> {
			if (LocalDateTime.now().isAfter(el.getDataConsegna().plusDays(10))) {
				el.setDataEliminazione(LocalDateTime.now());
				DeleteMessage deleteMessage = new DeleteMessage(ConstantColossium.MY_CHAT_ID, el.getId());
				try {
					entityManager.persist(el);
					execute(deleteMessage);
				} catch (TelegramApiException e) {
					throw new RuntimeException(e);
				}
			}
		});
	}

	@Bean(name = "readerNews")
	public ItemReader<News> itemReaderNews() {
		return () -> {
			if (posizioneNews==0) {
				init();
			}
			if (posizioneNews>=listNews.size()) return null;
			News  newsAtt = listNews.get(posizioneNews);
			posizioneNews++;
			return newsAtt;
		};
	}

	@Bean(name = "readerShow")
	public ItemReader<Show> itemReaderShow() {
		return () -> {
			if (posizioneShow>=listShow.size()) return null;
			Show  showAtt = listShow.get(posizioneShow);
			posizioneShow++;
			return showAtt;
		};
	}
	
	@Bean(name = "processorNews")
	public ItemProcessor<News, News> itemProcessorNews() {
		return item -> {
			List<News> resultList = entityManager.createQuery("select n from News n where des = :des and titolo = :titolo", News.class)
					.setParameter("des", item.getDes())
					.setParameter("titolo", item.getTitolo())
					.getResultList();
			if (resultList.size()==0) {
				item.setDataConsegna(LocalDateTime.now());
			}
			return item;
		};
	}

	@Bean(name = "processorShow")
	public ItemProcessor<Show, Show> itemProcessorShow() {
		return item -> {
			List<Show> resultList = entityManager.createQuery("select n from Show n where titolo = :titolo", Show.class)
					.setParameter("titolo", item.getTitolo())
					.getResultList();
			if (resultList.size()==0) {
				item.setDataConsegna(LocalDateTime.now());
			}
			return item;
		};
	}
	
	@Bean(name = "writerNews")
	public ItemWriter<News> itemWriterNews() {
		return news -> news.forEach(el -> {
			if (el.getDataConsegna()!=null) {
				entityManager.persist(el);
				inviaMessaggio(el.toString());
				messaggiInviati++;
				if (messaggiInviati==ConstantColossium.MAX_NEWS) {
					inviaMessaggio("Leggi le NEWS!!!!!!!");
				}
			}
		});
	}

	@Bean(name = "writerShow")
	public ItemWriter<Show> itemWriterShow() {
		return shows -> shows.forEach(el -> {
			if (el.getDataConsegna()!=null) {
				entityManager.persist(el);
				sendImageToChat(el.getImg(),el.toString());
			}
		});
	}
	
	@Bean
	public Job createJob() {
		return jobBuilderFactory.get("MyJob")
				.incrementer(new RunIdIncrementer())
				.start(stepBeanNews)
				.next(stepBeanShow)
				.build();
	}


	@Bean(name = "stepNews")
	public Step createStepNews() {
		return stepBuilderFactory.get("StepNews")
				.<News, News> chunk(ConstantColossium.CHUNK)
				.reader(itemReaderNews)
				.processor(itemProcessorNews)
				.writer(itemWriterNews)
				.build();
	}	

	@Bean(name = "stepShow")
	public Step createStepShow() {
		return stepBuilderFactory.get("StepShow")
				.<Show, Show> chunk(ConstantColossium.CHUNK)
				.reader(itemReaderShow)
				.processor(itemProcessorShow)
				.writer(itemWriterShow)
				.build();
	}	

	@Override
	public String getBotUsername() {
		return ConstantColossium.BOT_USERNAME;
	}

	@Override
	public String getBotToken() {
		return ConstantColossium.BOT_TOKEN;
	}


	public void sendImageToChat(String imageUrl, String caption) {
	    SendPhoto sendPhoto = new SendPhoto();
	    sendPhoto.setChatId(ConstantColossium.MY_CHAT_ID);
	    sendPhoto.setPhoto(new InputFile(imageUrl));
	    sendPhoto.setCaption(caption);
	    try {
	        Message message = execute(sendPhoto);
			TelegramMsg tm = new TelegramMsg(message.getMessageId(), LocalDateTime.now());
			entityManager.persist(tm);
	    } catch (TelegramApiException e) {
			//throw new RuntimeException(e);
	    	inviaMessaggio("**** NO IMG *** <br>" + caption);
	    }
	}
	
	private void inviaMessaggio(String msg)  {
		SendMessage sendMessage = new SendMessage();
		sendMessage.enableHtml(true);
		sendMessage.setParseMode("html");
		sendMessage.setChatId(ConstantColossium.MY_CHAT_ID);
		sendMessage.setText(msg);
		try {
			Message message = execute(sendMessage);
			TelegramMsg tm = new TelegramMsg(message.getMessageId(), LocalDateTime.now());
			entityManager.persist(tm);
		} catch (TelegramApiException e) {
			throw new RuntimeException(e);
		}
	}
	@Override
	public void onUpdateReceived(Update update) {
	}

	int messaggiInviati=0;
	private List<News> listNews=new ArrayList<>(); 
	private List<Show> listShow=new ArrayList<>(); 
	int posizioneNews=0;
	int posizioneShow=0;
	

}
