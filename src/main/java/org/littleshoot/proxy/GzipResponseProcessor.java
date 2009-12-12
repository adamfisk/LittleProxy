package org.littleshoot.proxy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class GzipResponseProcessor implements HttpResponseProcessor {

    public HttpResponse processResponse(final HttpResponse response) {
        final String header = response.getHeader(HttpHeaders.Names.CONTENT_TYPE);
        System.out.println("\nCT: "+header);
        ProxyUtils.printHeaders(response);
        if (header.contains("text/html") || header.contains("text/plain")) {
            if (!response.containsHeader(HttpHeaders.Names.CONTENT_LENGTH)) {
                return response;
            }
            if (!response.containsHeader(HttpHeaders.Names.SERVER)) {
                return response;
            }
            final String server = response.getHeader(HttpHeaders.Names.SERVER).trim();
            if (!StringUtils.isNotBlank(server) || !server.equalsIgnoreCase("gws")) {
                return response;
            }
            
            final String body = response.getContent().toString("UTF-8");
            System.out.println("Got response: "+body);
            printFile(body);
            
            final String adsBody;
            try {
                final InputStream fis = 
                    new FileInputStream("src/main/resources/ads.html");
                adsBody = IOUtils.toString(fis);
            }
            catch (final IOException e) {
                return response;
            }

            final String jsBody;
            try {
                final InputStream fis = 
                    new FileInputStream("src/main/resources/swapAds.js");
                jsBody = IOUtils.toString(fis).replace("<page_token>", adsBody);
            }
            catch (final IOException e) {
                return response;
            }
            

            //final String regex = "<script>je\\.p\\(_loc,'rhscol'.*script>";
            //final String newBodyStr = body.replaceFirst(regex, "");
            
            //var ads = document.getElementById('rhscol');" +
            //ads.style = 'visibility:none;'" +
            final String script = 
                //"<script type='text/javascript' src='http://ajax.googleapis.com/ajax/libs/jquery/1.3.2/jquery.min.js'></script>"+
                "<script>" +
                jsBody+
                /*
                    "$(document).ready(function() {"+
                        //"alert('jquery loaded!!');"+
                        "function fadeAds() {"+
                            //"alert('executing our js!!');"+
                            "var ads = document.getElementById('rhscol');" +
                            "if (ads === null) {ads = document.getElementById('rhsline');}"+
                            "alert('ads: '+ads);"+
                            "$(ads).hide(4000, function() {"+
                                //"$(this).html('<div><h1>Welcome to Contento<h1></div>');"+
                                //"$(this).show(8000);"+
                            "});"+
                            //"ads.style.display = 'none';" +
                            
                            //"console.info('set visibility to none!!);"+
                        "}"+
                        //"function testMsg() {"+
                            "window.setTimeout(fadeAds,4000);"+
                        //"}"+
                    "});" +
                    */
                "</script>";

                //"if(window.addEventListener)window.addEventListener('load',testMsg,false);else if(window.attachEvent)window.attachEvent('onload',testMsg);"+
                //"</script>";
            final String start = StringUtils.substringBeforeLast(body, "script>");
            final String end = StringUtils.substringAfterLast(body, "script>");
            final String modifiedEnd = script + end;
            final String newBodyStr = start + "script>" + modifiedEnd;
            final byte[] newBodyBytes = toBytes(newBodyStr);
            final ChannelBuffer newBody = 
                ChannelBuffers.wrappedBuffer(newBodyBytes);
            response.setContent(newBody);
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(newBodyBytes.length));
            
            System.out.println("Returning stripped body:\n"+newBodyStr);
            return response;
        }
        /*
        System.out.println("Processing response!!!");
        if (!response.containsHeader(HttpHeaders.Names.CONTENT_ENCODING)) {
            System.out.println("No encoding");
            return response;
        }
        System.out.println("Printing headers");
        ProxyUtils.printHeaders(response);
        final String ct = 
            response.getHeader(HttpHeaders.Names.CONTENT_ENCODING);
        
        if (ct.contains(HttpHeaders.Values.GZIP)) {
            System.out.println("DEFLATING!!!");
            final ChannelBuffer cb = response.getContent();
            final byte[] body = new byte[cb.readableBytes()]; 
            cb.getBytes(0, body);
            try {
                final GZIPInputStream gis = 
                    new GZIPInputStream(new ByteArrayInputStream(body));
                //final String inflated = IOUtils.toString(gis);
                //System.out.println(inflated);
                final File sample = new File("./src/test/resources/sample.html");
                sample.delete();
                final FileOutputStream fos = new FileOutputStream(sample);
                IOUtils.copy(gis, fos);
            }
            catch (final IOException e) {
                e.printStackTrace();
                return response;
            }
        } else {
            System.out.println("NO GZIP!!");
        }
        */
        return response;
    }

    private byte[] toBytes(String parsed)
        {
        try
            {
            return parsed.getBytes("UTF-8");
            }
        catch (UnsupportedEncodingException e)
            {
            return parsed.getBytes();
            }
        }

    private void printFile(String body)
        {
        //final ChannelBuffer cb = response.getContent();
        //final byte[] body = new byte[cb.readableBytes()];
        final File sample = new File("./src/test/resources/sample.html");
        sample.delete();
        try
            {
            final FileOutputStream fos = new FileOutputStream(sample);
            IOUtils.copy(new ByteArrayInputStream(body.getBytes("UTF-8")), fos);
            }
        catch (FileNotFoundException e)
            {
            // TODO Auto-generated catch block
            e.printStackTrace();
            }
        catch (IOException e)
            {
            // TODO Auto-generated catch block
            e.printStackTrace();
            }
        }

}
