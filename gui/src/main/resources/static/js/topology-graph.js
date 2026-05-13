(function () {
    function readNodes() {
        return Array.from(document.querySelectorAll('.topology-node-data')).map((el) => ({
            id: Number(el.dataset.id),
            name: el.dataset.name,
            ip: el.dataset.ip,
            status: el.dataset.status,
            previous: Number(el.dataset.previous),
            next: Number(el.dataset.next),
            selected: el.dataset.selected === 'true'
        }));
    }

    function radiusFor(node, selected, nodesById) {
        if (!selected) return 30;
        if (node.id === selected.id) return 58;
        if (node.id === selected.previous || node.id === selected.next) return 44;
        return 30;
    }

    function makeSvg(tag) {
        return document.createElementNS('http://www.w3.org/2000/svg', tag);
    }

    function draw() {
        const svg = document.getElementById('topology-graph');
        if (!svg) return;

        const nodes = readNodes();
        svg.innerHTML = '';

        if (nodes.length === 0) return;

        const width = 1000;
        const height = 560;
        const cx = width / 2;
        const cy = height / 2;
        const ringRadius = nodes.length === 1 ? 0 : Math.min(width, height) * 0.33;
        const nodesById = new Map(nodes.map((n) => [n.id, n]));
        const selected = nodes.find((n) => n.selected) || nodes[0];

        nodes.forEach((node, index) => {
            const angle = -Math.PI / 2 + (2 * Math.PI * index) / nodes.length;
            node.x = cx + ringRadius * Math.cos(angle);
            node.y = cy + ringRadius * Math.sin(angle);
            node.r = radiusFor(node, selected, nodesById);
        });

        // Draw arrows first, below nodes.
        nodes.forEach((node) => {
            const target = nodesById.get(node.next);
            if (!target || target.id === node.id) return;

            const dx = target.x - node.x;
            const dy = target.y - node.y;
            const length = Math.sqrt(dx * dx + dy * dy);
            if (length < 1) return;

            const ux = dx / length;
            const uy = dy / length;

            const startX = node.x + ux * (node.r + 10);
            const startY = node.y + uy * (node.r + 10);
            const endX = target.x - ux * (target.r + 20);
            const endY = target.y - uy * (target.r + 20);

            const line = makeSvg('line');
            line.setAttribute('x1', startX);
            line.setAttribute('y1', startY);
            line.setAttribute('x2', endX);
            line.setAttribute('y2', endY);
            line.setAttribute('stroke', '#b10097');
            line.setAttribute('stroke-width', '4');
            line.setAttribute('stroke-linecap', 'round');
            line.setAttribute('opacity', '0.78');
            svg.appendChild(line);

            const size = 18;
            const px = -uy;
            const py = ux;
            const tipX = endX + ux * size;
            const tipY = endY + uy * size;
            const baseX = endX;
            const baseY = endY;

            const polygon = makeSvg('polygon');
            polygon.setAttribute('points', [
                `${tipX},${tipY}`,
                `${baseX + px * (size * 0.55)},${baseY + py * (size * 0.55)}`,
                `${baseX - px * (size * 0.55)},${baseY - py * (size * 0.55)}`
            ].join(' '));
            polygon.setAttribute('fill', '#b10097');
            polygon.setAttribute('opacity', '0.9');
            svg.appendChild(polygon);
        });

        nodes.forEach((node) => {
            const group = makeSvg('g');
            group.classList.add('topology-node');
            group.setAttribute('data-id', node.id);

            const circle = makeSvg('circle');
            circle.setAttribute('cx', node.x);
            circle.setAttribute('cy', node.y);
            circle.setAttribute('r', node.r);
            circle.setAttribute('fill', node.id === selected.id ? '#b10097' : (node.id === selected.previous || node.id === selected.next ? '#3b38a3' : '#e4c1db'));
            circle.setAttribute('stroke', '#08071f');
            circle.setAttribute('stroke-width', node.id === selected.id ? '5' : '3');
            group.appendChild(circle);

            const title = makeSvg('text');
            title.setAttribute('x', node.x);
            title.setAttribute('y', node.y - 4);
            title.setAttribute('text-anchor', 'middle');
            title.setAttribute('font-size', node.id === selected.id ? '17' : '14');
            title.setAttribute('font-weight', '700');
            title.setAttribute('fill', node.id === selected.id || node.id === selected.previous || node.id === selected.next ? '#ffffff' : '#002e65');
            title.textContent = node.name;
            group.appendChild(title);

            const subtitle = makeSvg('text');
            subtitle.setAttribute('x', node.x);
            subtitle.setAttribute('y', node.y + 18);
            subtitle.setAttribute('text-anchor', 'middle');
            subtitle.setAttribute('font-size', '12');
            subtitle.setAttribute('fill', node.id === selected.id || node.id === selected.previous || node.id === selected.next ? '#ffffff' : '#002e65');
            subtitle.textContent = node.id;
            group.appendChild(subtitle);

            group.addEventListener('click', () => {
                window.location.href = `/dashboard?selectedId=${node.id}#nodes`;
            });

            svg.appendChild(group);
        });
    }

    window.addEventListener('load', draw);
    window.addEventListener('resize', draw);
})();
