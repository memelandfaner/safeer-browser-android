# Safeer Browser 1.0.20

**Safeer Link brez televizorja: vsaka naprava je središče, vsaka pošilja in sprejema.**

- Središče Safeer Linka je lahko tudi telefon: v meniju → Safeer Link ga vklopiš in ostale naprave (računalnik, televizor, tablica) se povežejo nanj. Televizor ni več potreben.
- Vsaka naprava lahko drugi pošlje stran, besedilo, datoteko ali svoj zaslon — v katerokoli smer. Telefon vse to tudi sprejme: stran se odpre v brskalniku, besedilo v oknu, datoteka pristane v mapi prenosov, zaslon druge naprave se prikaže kot stran.
- Sprejem deluje v ozadju: dokler je telefon seznanjen s središčem, ga druge naprave dosežejo tudi takrat, ko Safeer Link ni odprt (obvestilo »Safeer Link: pripravljen na sprejem«). Ko brskalnik ni v ospredju, prejeto pride kot obvestilo.
- Ko središče ugasne (npr. televizor), telefon sam poišče drugo v omrežju; če je bil z njim že seznanjen, se poveže brez nove kode. Seznanitve z več središči si zapomni po ključu vsakega.
- Koda za seznanitev se pokaže v oknu čez odprto stran (in v obvestilu), tudi ko stran Safeer Linka ni odprta.
- Vse povezave med napravami so šifrirane (TLS) in vezane na ključ središča; 6-mestna koda nikoli ne potuje po omrežju (SPAKE2). Naprave s starejšo različico se z novim središčem ne morejo seznaniti — posodobi vse hkrati.
- Neveljavno potrdilo spletne strani povezavo ustavi, namesto da bi jo tiho spustilo naprej.
- Stran Safeer Linka je enaka na telefonu, računalniku in televizorju.

Preverjeno v živo na telefonu, tablici, računalniku (Linux) in televizorju v vseh smereh.
