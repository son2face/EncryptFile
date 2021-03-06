package Module.File;

import Module.ComposerModel.AccessInfo;
import Module.ComposerModel.Crypto;
import Module.ComposerModel.FileEncrypted;
import Module.ComposerModel.Identity;
import Module.EncryptKey.CryptoEntity;
import Module.EncryptKey.EncryptKeyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by Son on 6/15/2017.
 */
public class FileService {
    public String restUrl;
    public String filePath;
    public EncryptKeyService encryptKeyService;

    public FileService() {
        this.restUrl = System.getenv("REST_URL");
        this.restUrl = this.restUrl == null ? "http://localhost:3000" : this.restUrl;
        this.filePath = System.getenv("FILE_PATH");
        this.filePath = this.filePath == null ? "files" : this.filePath;
        encryptKeyService = new EncryptKeyService();
    }

    public EncryptFileEntity upload(FileEntity fileEntity,
                                    FormDataBodyPart content,
                                    FormDataContentDisposition contentDisposition,
                                    final InputStream input) throws Exception {
//        for (final File fileEntry : files.listFiles()) {
//            if (fileEntry.isDirectory()) {
//                addJavaFiles(fileEntry);
//            } else{
//                System.out.println(fileEntry.getPath());
//              fileEntry.getPath()
//            }
//        }
        CryptoEntity cryptoEntity = new CryptoEntity();
        cryptoEntity.certificate = fileEntity.certificate;
        cryptoEntity.sign = fileEntity.sign;
        cryptoEntity.data = fileEntity.message;
        if (!this.encryptKeyService.verify(cryptoEntity)) throw new Exception("error 2");
        ObjectMapper mapper = new ObjectMapper();
        FileEntity readValue = mapper.readValue(cryptoEntity.data, FileEntity.class);
        String[] s = fileEntity.src.split("/");
        s[s.length - 1] = s[s.length - 1] + fileEntity.hash;
        String pathFile = String.join("/", s);
        File file = new File(this.filePath + "/" + pathFile);
        if (file.exists()) throw new Exception("error 5");
        List<Identity> identities = mapper.readValue(new URL(this.restUrl + "/api/system/identities"), new TypeReference<List<Identity>>() {
        });
        Identity identity = identities.parallelStream().filter(x -> x.certificate.equals(fileEntity.certificate) && x.state.equals("ACTIVATED")).findFirst().orElse(null);
        if (identity == null) throw new Exception("error 16");
        List<FileEncrypted> ff = mapper.readValue(new URL(this.restUrl + "/api/FileEncrypted"), new TypeReference<List<FileEncrypted>>() {
        });
        FileEncrypted blockchainInfo = ff.stream().filter(x -> x.uid.equals(readValue.src)).findFirst().orElse(null);
        if (blockchainInfo == null) throw new Exception("error 3");
        if (blockchainInfo.checksum.equals(readValue.hash) || Arrays.asList(blockchainInfo.propose_list).parallelStream().anyMatch(c -> c.proposing_file.checksum.equals(readValue.hash))) {
            file.getParentFile().mkdirs();
            FileUtils.copyInputStreamToFile(input, file);
        } else {
            throw new Exception("error 7");
        }
        //TODO: Check version
        //TODO: Check type
        return new EncryptFileEntity();
    }

    public void download(FileEntity fileEntity, OutputStream outputStream) throws Exception {
        //TODO: Send signature = Increased Number,
        //TODO: Check permission
        //TODO: Get File
        //TODO: Decrypt If needed
        CryptoEntity cryptoEntity = new CryptoEntity();
        cryptoEntity.certificate = fileEntity.certificate;
        cryptoEntity.sign = fileEntity.sign;
        cryptoEntity.data = fileEntity.message;
        if (!this.encryptKeyService.verify(cryptoEntity)) throw new Exception("error 2");
        ObjectMapper mapper = new ObjectMapper();
        FileEntity readValue = mapper.readValue(cryptoEntity.data, FileEntity.class);

        List<FileEncrypted> ff = mapper.readValue(new URL(this.restUrl + "/api/FileEncrypted"), new TypeReference<List<FileEncrypted>>() {
        });
        FileEncrypted blockchainInfo = ff.stream().filter(x -> x.uid.equals(readValue.src)).findFirst().orElse(null);
        if (blockchainInfo == null) throw new Exception("error 3");
        if (blockchainInfo.checksum.equals(readValue.hash) || Arrays.asList(blockchainInfo.propose_list).parallelStream().anyMatch(c -> c.proposing_file.checksum.equals(readValue.hash))) {
            List<Identity> identities = mapper.readValue(new URL(this.restUrl + "/api/system/identities"), new TypeReference<List<Identity>>() {
            });
            Identity identity = identities.parallelStream().filter(x -> x.certificate.equals(fileEntity.certificate) && x.state.equals("ACTIVATED")).findFirst().orElse(null);
            if (identity == null) throw new Exception("error 16");
            AccessInfo accessInfo = Arrays.stream(blockchainInfo.access_info_list).filter(t -> t.user.equals(identity.participant)).findFirst().orElse(null);
            if (accessInfo == null) {
                throw new Exception("error 17");
            }
            List<Crypto> cryptoList = Arrays.stream(accessInfo.crypto_list).filter(t -> t.identity.equals("resource:org.hyperledger.composer.system.Identity#" + identity.identityId)).collect(Collectors.toList());
            boolean check = Arrays.stream(blockchainInfo.control_info.required_list)
                    .allMatch(s -> cryptoList.parallelStream().anyMatch(k -> k.issuer.equals(s)));
            check = check && (Arrays.stream(blockchainInfo.control_info.optional_list)
                    .filter(s -> cryptoList.parallelStream()
                            .anyMatch(k -> k.issuer.equals(s))).count() >= blockchainInfo.control_info.thresh_hold);
            if (!check) throw new Exception("error 18");
            File file = new File(this.filePath + "/" + readValue.src + readValue.hash);
            if (!file.exists()) throw new Exception("error 9");
            JSONObject obj = new JSONObject();
            obj.put("user",identity.participant);
            obj.put("file", "resource:file.FileEncrypted#" + blockchainInfo.uid);
            obj.put("action", "DOWNLOAD");
            String json = obj.toJSONString();
            StringEntity entity = new StringEntity(json,
                    ContentType.APPLICATION_JSON);
            HttpClient httpClient = HttpClientBuilder.create().build();
            HttpPost request = new HttpPost(this.restUrl + "/api/LogRequest");
            request.setEntity(entity);
            HttpResponse response = httpClient.execute(request);
            if(response.getStatusLine().getStatusCode() == 200){
                IOUtils.copy(new FileInputStream(file), outputStream);
            } else throw new Exception("error 19");
        } else {
            throw new Exception("error 7");
        }
    }

    public void cleanFolder() {
        //TODO: Check version
        //TODO: ......
    }
}
