package br.ars.user_service.dto;

import java.util.List;

public class RegisterRequest {
    public String nome;
    public String email;
    public String senha;
    public String tipo; // Enum: PROFISSIONAL, CONSULTOR, EMPRESA
    public String bio;
    public List<String> tags;
    public String avatarUrl;
}