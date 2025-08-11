package br.ars.user_service.dto;

import java.util.List;

public class RegisterRequest {
    public String nome;
    public String email;
    public String telefone;
    public String senha;
    public String tipo; // Enum: PROFISSIONAL, CONSULTOR
    public String bio;
    public List<String> tags;

    // Usado opcionalmente como baseName; a URL final é calculada após upload.
    public String avatarUrl;

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getTelefone() { return telefone; }
    public void setTelefone(String telefone) { this.telefone = telefone; }
    public String getSenha() { return senha; }
    public void setSenha(String senha) { this.senha = senha; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
