package it.daniele.colossium.batch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import it.daniele.colossium.domain.News;
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
	ItemReader<News> itemReader;

	@Autowired
	ItemProcessor<News,News> itemProcessor;

	@Autowired
	ItemWriter<News> itemWriter;

	@PersistenceContext
	EntityManager entityManager;

	
	
	
	@Autowired
	JobBuilderFactory jobBuilderFactory;

	@Autowired
	Step stepBean;

	@PostConstruct
	private void postConstructor() {
//		init();
		String response = restTemplate.getForObject("https://www.teatrocolosseo.it/News/Default.aspx", String.class);
		Document doc = Jsoup.parse(response);
		for (int i=0;i<ConstantColossium.MAX_NEWS;i++) {
			String textDes = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblDescrizione1]").text();
			String textData = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblData]").text();
			String textTitolo = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblTitolo]").text();
			News news = new News(textData, textTitolo, textDes);
			listNews.add(news);
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

	@Bean
	public ItemReader<News> itemReader() {
		return () -> {
			if (posizione==0) {
				init();
			}
			if (posizione>=listNews.size()) return null;
			News  customerCreditAtt = listNews.get(posizione);
			posizione++;
			return customerCreditAtt;
		};
	}

	@Bean
	public ItemProcessor<News, News> itemProcessor() {
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

	@Bean
	public ItemWriter<News> itemWriter() {
		return customers -> customers.forEach(el -> {
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

	@Bean
	public Job createJob() {
		return jobBuilderFactory.get("MyJob")
				.incrementer(new RunIdIncrementer())
				.flow(stepBean).end().build();
	}


	@Bean
	public Step createStep() {
		return stepBuilderFactory.get("MyStep")
				.<News, News> chunk(ConstantColossium.CHUNK)
				.reader(itemReader)
				.processor(itemProcessor)
				.writer(itemWriter)
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
	int posizione=0;

}
