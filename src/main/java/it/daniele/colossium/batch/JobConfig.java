package it.daniele.colossium.batch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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


@SuppressWarnings("deprecation")
@Configuration
@EnableBatchProcessing
public class JobConfig extends TelegramLongPollingBot {

	RestTemplate restTemplate=new RestTemplate();
	Logger logger=LoggerFactory.getLogger(this.getClass());

	@PersistenceContext
	EntityManager entityManager;
	
	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	StepBuilderFactory stepBuilderFactory;

	@Autowired
	JobBuilderFactory jobBuilderFactory;
	
	

	@Bean
	public Job createJob() {
		return jobBuilderFactory.get("MyJob")
				.incrementer(new RunIdIncrementer())
				.listener(jobResultListener())
				.start(initStep())
				.next(stepNews())
				.next(stepShow())
				.build();
	}

	private JobExecutionListener jobResultListener(){
		return new JobExecutionListener() {
			public void beforeJob(JobExecution jobExecution) {
				logger.debug("Called beforeJob");
			}
			public void afterJob(JobExecution jobExecution) {
				if (jobExecution.getStatus() == BatchStatus.COMPLETED ) {
			    	inviaMessaggio(esito);
					logger.info("COMPLETED: {}", jobExecution);
			    }
			    else if (jobExecution.getStatus() == BatchStatus.FAILED) {
			    	inviaMessaggio("ERRORE");
			    	logger.info("FAILED: {}", jobExecution);
			    }
			}
		};
	}

	private StepExecutionListener stepResultListener() {
    	
        return new StepExecutionListener() {
        	@Override
        	public void beforeStep(StepExecution stepExecution) {
        		logger.debug("Called beforeStep: {}", stepExecution);
        	}
        	@Override
        	public ExitStatus afterStep(StepExecution stepExecution) {
        		logger.info("Called afterStep: {}", stepExecution);
        		if (!stepExecution.getStepName().equals("initStep")) {
            		esito=esito+stepExecution.getStepName() + ":" + stepExecution.getWriteCount() + "\n\r";
        		}
        		return null;
        	}
        };
    }	

	private Step initStep() {
		return stepBuilderFactory.get("initStep")
				.tasklet((contribution, chunkContext) -> {
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

					List<TelegramMsg> resultList = entityManager.createQuery("select t from TelegramMsg t where dataEliminazione is null", TelegramMsg.class).getResultList();
					resultList.forEach(el-> {
						if (LocalDateTime.now().isAfter(el.getDataConsegna().plusDays(ConstantColossium.DAY_TTL))) {
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
					return RepeatStatus.FINISHED;
				})
				.listener(stepResultListener())
				.build();
	}

	private Step stepNews() {
		return stepBuilderFactory.get("StepNews")
				.<News, News> chunk(ConstantColossium.CHUNK)
				.reader(readerNews())
				.processor(processorNews())
				.writer(writerNews())
				.listener(stepResultListener())
				.build();
	}	
    
	private ItemReader<News> readerNews() {
		return () -> {
			if (posizioneNews>=listNews.size()) return null;
			News  newsAtt = listNews.get(posizioneNews);
			posizioneNews++;
			return newsAtt;
		};
	}

	private ItemProcessor<News, News> processorNews() {
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

	private ItemWriter<News> writerNews() {
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

	private Step stepShow() {
		return stepBuilderFactory.get("StepShow")
				.<Show, Show> chunk(ConstantColossium.CHUNK)
				.reader(readerShow())
				.processor(processorShow())
				.writer(writerShow())
				.listener(stepResultListener())
				.build();
	}	

	private ItemReader<Show> readerShow() {
		return () -> {
			if (posizioneShow>=listShow.size()) return null;
			Show  showAtt = listShow.get(posizioneShow);
			posizioneShow++;
			return showAtt;
		};
	}

	private ItemProcessor<Show, Show> processorShow() {
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

	private ItemWriter<Show> writerShow() {
		return shows -> shows.forEach(el -> {
			if (el.getDataConsegna()!=null) {
				entityManager.persist(el);
				sendImageToChat(el.getImg(),el.toString());
			}
		});
	}

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
		jdbcTemplate.update("insert into telegram_msg (id,data_consegna) values (?,?)", new Object[] {message.getMessageId(), LocalDateTime.now()});
	}
	
	private void inviaMessaggio(String msg)  {
		SendMessage sendMessage = new SendMessage();
		sendMessage.enableHtml(true);
		sendMessage.setParseMode("html");
		sendMessage.setChatId(ConstantColossium.MY_CHAT_ID);
		sendMessage.setText(msg);
		try {
			Message message = execute(sendMessage);
			salvaMessaggio(message);
		} catch (TelegramApiException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void sendImageToChat(String imageUrl, String caption) {
		SendPhoto sendPhoto = new SendPhoto();
		sendPhoto.setChatId(ConstantColossium.MY_CHAT_ID);
		sendPhoto.setPhoto(new InputFile(imageUrl));
		sendPhoto.setCaption(caption);
		try {
			Message message = execute(sendPhoto);
			salvaMessaggio(message);
		} catch (TelegramApiException e) {
			inviaMessaggio("**** NO IMG *** \n\r" + caption);
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
	String esito="";
	


}
