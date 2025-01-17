package org.rsj.analysis.core;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.rsj.analysis.dic.Dictionaries;

public class Segmenter {
	private Reader input;
	private AnalysisContext context;
	//分词歧义裁决器
	private Ambiguity ambiguity;
	
	/*
	 * 当letterStart > -1 时，标识当前的分词器正在处理英文和数字字符
	 */
	private int letterStart;
	private int letterEnd;
	private boolean isPureAbaric;
	
	private Token lastDictToken;//记录前一个匹配的最短的字典，为了避免字母重负分词
	
	/**
	 * 分词器构造函数
	 * @param input
     */
	public Segmenter(Reader input){
		this.input = input;
        this.context = new AnalysisContext();
		this.ambiguity = new Ambiguity();
		letterStart = -1;letterEnd = -1;
		lastDictToken = null;
	}
	
	/**
	 * 获取所有分词结果
	 * @return 
	 * @throws java.io.IOException
	 */
	public synchronized List<String> parseAll()throws IOException{ 
		List<String> allResult = new ArrayList<String>();
		Token token ;
		while ((token = this.next()) != null) {
			allResult.add(token.getText());
		}
		return allResult;
	}

	/**
	 * 分词，获取下一个词元
	 * @return Lexeme 词元对象
	 * @throws java.io.IOException
	 */
	public synchronized Token next()throws IOException{
		Token l = null;
		while((l = context.getNextToken()) == null ){
			/*
			 * 从reader中读取数据，填充buffer
			 * 如果reader是分次读入buffer的，那么buffer要  进行移位处理
			 * 移位处理上次读入的但未处理的数据
			 */
			int available = context.fillBuffer(this.input);
			if(available <= 0){
				//reader已经读完
				context.reset();
				return null;
				
			}else{
				//初始化指针
//				System.out.println("new Buffer read");
				context.initCursor();
//				System.out.println(context.getSegmentBuff());
//				System.out.println("init anaylze: the cursor is :" + context.getCursor());
				do{
        			anaylyze(context);//对一次读入的context中的buffer进行分析
        			//字符缓冲区接近读完，需要读入新的字符,且当前碰到了一个空闲字符
        			if(context.needRefillBuffer()){
        				break;
        			}
   				//向前移动指针
				}while(context.moveCursor());
				//重置子分词器，为下轮循环进行初始化
				reset();
				
			}
//			System.out.println("before process arbitrator!let's see the orgTokens:");
//			System.out.println(context.getOrgListsWhitConflict().size());
//			for (ConflictTokensList conflictList : context.getOrgListsWhitConflict()) {
//				System.out.println("this is a new conflict list");
//				for (Token token : conflictList.getConflictList()) {
//					token.setText(String.valueOf(context.getSegmentBuff() , token.getBegin() , token.getLength()));
//					System.out.println(token.toString());
//				}
//				
//			}
			//对分词进行歧义处理
//			context.printlnAllSegment();
			this.ambiguity.process(context);
			
//			System.out.println("/n/nafter process arbitrator!!!let's see the orgTokens:");
//			System.out.println(context.getOrgListsWhitConflict().size());
//			System.out.println("and the real result:");
//			System.out.println(context.getResults().size());
//			context.printlnAllResult();
//				
//				//将分词结果输出到结果集，并处理未切分的单个CJK字符
//				context.outputToResult();
//				
//				System.out.println("/n/nafter outputToResult!!!!!,see the real result:");
//				System.out.println(context.getResults().size());
//				context.printlnAllResult();
			
			//记录本次分词的缓冲区位移
			context.markBufferOffset();			
		}
		return l;
	}
		
	public synchronized AnalysisContext getContext() {
		return context;
	}

	/**
     * 重置分词器到初始状态
     * @param input
     */
	public synchronized void reset() {
//		context.reset();
		letterStart = -1;letterEnd = -1;
		lastDictToken = null;
	}
	
	//分词开始的地方！同时分析中文，英文，数字
	public void anaylyze(AnalysisContext context) {
		if(CharacterUtil.CHAR_USELESS != context.getCurrentCharType()){
			LinkedList<Token> tokensForThisChar = Dictionaries.getSingleton().matchInMainDict(context.getBuffOffset(), context.getSegmentBuff(), context.getCursor());
			
			if(!tokensForThisChar.isEmpty()){//是字典中的词
				if(letterStart != -1) {//已经再处理了数字和字母，则此时碰到了一个字典中词，则输出已经保存的数字或字母
					outPutLetterOrArabic();
				}
//				System.out.println("this char contain word count:"+ tokensForThisChar.size());
				//将结果加入
				for (Token token : tokensForThisChar) {
//					System.out.println("the token is:"+ token.toString());
					context.addTokenToListWhitConflict(token);
//					this.segment.addToSentenceSeg(token);
				}
				lastDictToken = tokensForThisChar.getFirst();
			}else if(CharacterUtil.CHAR_CHINESE == context.getCurrentCharType()) {//不是字典中的中文字，以单字输出token
				Token token = new Token(context.getBuffOffset(), context.getCursor(), 1, Token.CNWORD, "x");
				context.addTokenToListWhitConflict(token);
			}else if(lastDictToken == null || (context.getCursor() >= lastDictToken.getBegin() + lastDictToken.getLength())){//不是字典中的词，且是英文或者数字且当前指针超过上一个匹配上字典中的词的最短词的结尾，用analyzeLetter处理英文字符和阿拉伯数字
//				System.out.println("going to analyze letter: "+context.getCurrentChar());
				analyzeLetter(context);
			}
		}else if(letterStart != -1) {//已经再处理了数字和字母，则此时碰到了未识别字符
			if(isPureAbaric && CharacterUtil.isNumConnector(context.getCurrentChar())){//未识别字符是连接符
				this.letterEnd = context.getCursor();
			}else if(isPureAbaric && CharacterUtil.isLetterConnector(context.getCurrentChar())){
				isPureAbaric = false;
				this.letterEnd = context.getCursor();
			}else if(!isPureAbaric && CharacterUtil.isLetterConnector(context.getCurrentChar())){
				this.letterEnd = context.getCursor();
			}else {//未识别字符不是连接符，输出并作为新的句子成分
				outPutLetterOrArabic();
				context.addSentenceSegment();
			}
		}else {
			//context中生成新的输出句子片段
			context.addSentenceSegment();
//			context.addSentenceSegment(segment);
//			System.out.println("this is a new sentence segment");
//			for (Token token : segment.getConflictList()) {
//				token.setText(String.valueOf(context.getSegmentBuff() , token.getBegin() , token.getLength()));
//				System.out.println(token.toString());
//			}
//			segment = new SentenceSegment();
		}
//		//判断缓冲区是否已经读完
//		if(context.isBufferConsumed() && segment.getConflictList().size() != 0){
//			context.addSentenceSegment(segment);
//			System.out.println("this is a new sentence segment");
//			for (Token token : segment.getConflictList()) {
//				token.setText(String.valueOf(context.getSegmentBuff() , token.getBegin() , token.getLength()));
//				System.out.println(token.toString());
//			}
//			segment = new SentenceSegment();
//		}
	}
	
	public void outPutLetterOrArabic() {
		if(!isPureAbaric){
			Token token = new Token(context.getBuffOffset() , this.letterStart , this.letterEnd - this.letterStart + 1 , Token.LETTER, "m");
			context.addTokenToListWhitConflict(token);
//			System.out.println("add a letter: "+token.toString());
//			this.segment.addToSentenceSeg(new Token(context.getBufferOffset() , this.letterStart , this.letterEnd - this.letterStart + 1 , Token.LETTER, "m"));
		}else {
			Token token = new Token(context.getBuffOffset() , this.letterStart , this.letterEnd - this.letterStart + 1 , Token.ARABIC, "xx");
			context.addTokenToListWhitConflict(token);
//			System.out.println("add a arabic: "+token.toString());
//			this.segment.addToSentenceSeg(new Token(context.getBufferOffset() , this.letterStart , this.letterEnd - this.letterStart + 1 , Token.ARABIC, "xx"));
		}
		this.letterStart = -1;
		this.letterEnd = -1;
	}
	
	/**
	 * 处理数字字母混合输出
	 * @param context
	 * @return
	 */
	private void analyzeLetter(AnalysisContext context){
		if(this.letterStart == -1){//当前的分词器尚未开始处理字符
			if(CharacterUtil.CHAR_ARABIC == context.getCurrentCharType()
					|| CharacterUtil.CHAR_ENGLISH == context.getCurrentCharType()){
				//记录起始指针的位置,标明分词器进入处理状态
				this.letterStart = context.getCursor();
				this.letterEnd = letterStart;
				if(CharacterUtil.CHAR_ARABIC == context.getCurrentCharType())
					this.isPureAbaric = true;
			}
			
		}else{//当前的分词器正在处理字符			
			if(CharacterUtil.CHAR_ARABIC == context.getCurrentCharType()
					|| CharacterUtil.CHAR_ENGLISH == context.getCurrentCharType()){
				//记录下可能的结束位置
				this.letterEnd = context.getCursor();
				if(isPureAbaric && CharacterUtil.CHAR_ENGLISH == context.getCurrentCharType())
					isPureAbaric = false;
			}else{
				outPutLetterOrArabic();
			}			
		}
		//判断缓冲区是否已经读完
		if(context.isBufferConsumed() && (this.letterStart != -1 && this.letterEnd != -1)){
			outPutLetterOrArabic();
		}
	}
	
	
}
