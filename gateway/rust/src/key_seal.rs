use crate::db::{Result, error};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use std::{
    fs::{self, OpenOptions},
    io::{ErrorKind, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
};

pub struct KeySeal(LessSafeKey);

impl KeySeal {
    pub fn open(database: &Path) -> Result<Self> {
        let path = database.with_extension("api-key-seal");
        if !path
            .try_exists()
            .map_err(|_| error(500, "无法检查接口 Key 加密文件"))?
        {
            let mut secret = [0; 32];
            let mut suffix = [0; 12];
            getrandom::fill(&mut secret).map_err(|_| error(500, "无法生成接口 Key 加密密钥"))?;
            getrandom::fill(&mut suffix).map_err(|_| error(500, "无法生成随机文件名"))?;
            let temporary = path.with_extension(format!(
                "api-key-seal-{}.tmp",
                URL_SAFE_NO_PAD.encode(suffix)
            ));
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&temporary)
                .map_err(|_| error(500, "无法创建接口 Key 加密文件"))?;
            let created = file
                .write_all(&secret)
                .and_then(|_| file.sync_all())
                .and_then(|_| match fs::hard_link(&temporary, &path) {
                    Err(e) if e.kind() == ErrorKind::AlreadyExists => Ok(()),
                    result => result,
                });
            let _ = fs::remove_file(&temporary);
            created.map_err(|_| error(500, "无法保存接口 Key 加密文件"))?;
        }
        let metadata =
            fs::symlink_metadata(&path).map_err(|_| error(500, "无法读取接口 Key 加密文件"))?;
        if !metadata.is_file() || metadata.permissions().mode() & 0o077 != 0 {
            return Err(error(500, "接口 Key 加密文件须为仅当前用户可读写的文件"));
        }
        let secret = fs::read(&path).map_err(|_| error(500, "无法读取接口 Key 加密文件"))?;
        let key = UnboundKey::new(&AES_256_GCM, &secret)
            .map_err(|_| error(500, "接口 Key 加密文件无效"))?;
        Ok(Self(LessSafeKey::new(key)))
    }

    pub fn encrypt(&self, id: &str, secret: &str) -> Result<String> {
        let mut nonce = [0; 12];
        getrandom::fill(&mut nonce).map_err(|_| error(500, "无法生成加密随机数"))?;
        let mut data = secret.as_bytes().to_vec();
        self.0
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(nonce),
                Aad::from(id),
                &mut data,
            )
            .map_err(|_| error(500, "无法加密接口 Key"))?;
        Ok(URL_SAFE_NO_PAD.encode([nonce.as_slice(), data.as_slice()].concat()))
    }

    pub fn decrypt(&self, id: &str, encoded: &str) -> Result<String> {
        let invalid = |_| error(500, "无法解密接口 Key，请核对加密文件");
        let mut data = URL_SAFE_NO_PAD.decode(encoded).map_err(invalid)?;
        let (nonce, ciphertext) = data
            .split_first_chunk_mut::<12>()
            .ok_or_else(|| error(500, "接口 Key 密文无效"))?;
        let secret = self
            .0
            .open_in_place(
                Nonce::assume_unique_for_key(*nonce),
                Aad::from(id),
                ciphertext,
            )
            .map_err(|_| error(500, "无法解密接口 Key，请核对加密文件"))?;
        String::from_utf8(secret.to_vec()).map_err(|_| error(500, "接口 Key 密文无效"))
    }
}
